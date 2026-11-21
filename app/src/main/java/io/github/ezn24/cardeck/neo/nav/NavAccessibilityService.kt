package io.github.ezn24.cardeck.neo.nav

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.ezn24.cardeck.neo.iap2.RouteGuidanceUpdate.Lane
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher

/**
 * Reads the turn-by-turn card of navigation apps that publish nothing usable elsewhere (Waze: no notification
 * details; Amap: notification says only "navigating") from their on-screen text, for the head unit's cluster.
 *
 * Scope is deliberately narrow: events are limited to [PACKAGES] in the service config, and nothing is read
 * unless a head unit is subscribed to route guidance ([CarRouteGuidance.isWanted]). The maps usually run on the
 * CarPlayMap virtual display, so windows are taken from all displays. Reads are throttled to [READ_INTERVAL_MS]
 * and run on a background thread (each node access is an IPC).
 */
class NavAccessibilityService : AccessibilityService() {
    private lateinit var worker: Handler
    private var lastReadAt = 0L
    private var trailingPosted = false
    private var publishedSource: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        worker = Handler(HandlerThread("nav-a11y").apply { start() }.looper)
        instance = this
        log("NAV-A11Y: service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!CarRouteGuidance.isWanted || !::worker.isInitialized) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in PACKAGES) return
        worker.post { requestRead() }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        if (::worker.isInitialized) worker.looper.quitSafely()
        super.onDestroy()
    }

    /** Worker thread: read now, or once at the end of the throttle window. */
    private fun requestRead() {
        val now = SystemClock.uptimeMillis()
        val wait = lastReadAt + READ_INTERVAL_MS - now
        if (wait > 0) {
            if (!trailingPosted) {
                trailingPosted = true
                worker.postDelayed({
                    trailingPosted = false
                    requestRead()
                }, wait)
            }
            return
        }
        lastReadAt = now
        runCatching { read() }.onFailure { log("NAV-A11Y: read failed - ${it.message}") }
    }

    private fun read() {
        var info: NavInfo? = null
        val selectedMap = VirtualLauncher.activeMapPackage
        val mapDisplay = VirtualLauncher.mapDisplayId
        for (root in navRoots()) {
            val pkg = root.packageName?.toString() ?: continue
            if (selectedMap != null && pkg != selectedMap) continue
            val displayId = root.window?.displayId ?: -1
            if (mapDisplay >= 0 && displayId != mapDisplay) continue
            val nodes = ArrayList<Node>()
            collect(root, nodes, 0)
            info =
                when (pkg) {
                    WAZE -> wazeInfo(nodes)
                    AMAP -> NavText.fromAmapScreen(pkg, screenTexts(nodes), System.currentTimeMillis())
                    else -> NavText.fromTexts(pkg, nodes.mapNotNull { it.text ?: it.description }, System.currentTimeMillis())
                }
            if (info != null) {
                info = withArrow(info, nodes, displayId)
                if (SystemClock.uptimeMillis() < collectUntil) captureLaneRow(nodes, displayId)
                break
            }
        }
        runCatching { sampleGoogleMaps() }
        runCatching { readGoogleLanes() }
        publishedSource?.takeIf { it != info?.source }?.let { CarRouteGuidance.publish(it, null) }
        if (info != null) {
            publishedSource = info.source
            CarRouteGuidance.publish(info.source, info)
            // A static screen sends no accessibility events: keep reading while navigating so it is not taken for
            // "navigation ended" (CarRouteGuidance drops a source after 10 s without readings)
            worker.removeCallbacks(keepReading)
            worker.postDelayed(keepReading, pollDelay())
        } else {
            publishedSource = null
            lastInfo = null
            arrow = null
        }
    }

    private val keepReading =
        Runnable {
            if (!CarRouteGuidance.isWanted) return@Runnable
            requestRead()
            // Poll for as long as a head unit wants guidance, whether or not this read found anything: Waze's map is a
            // SurfaceView and its card may raise no accessibility events at all (2026-11-21: zero Waze readings in a
            // 10 min drive although NAV-DUMP parsed the screen fine).
            worker.removeCallbacks(keepReadingRef)
            worker.postDelayed(keepReadingRef, pollDelay())
        }
    private val keepReadingRef: Runnable get() = keepReading

    /** Poll slower while the next maneuver is far away: nothing on the card changes much there. */
    private fun pollDelay(): Long = if ((lastInfo?.second?.toManeuver?.meters ?: 0L) > FAR_FROM_MANEUVER_M) FAR_POLL_MS else KEEP_READING_MS

    private fun startPolling() {
        if (!::worker.isInitialized) return
        worker.removeCallbacks(keepReading)
        worker.post(keepReading)
    }

    private var lastLaneCaptureAt = 0L

    /**
     * Collection mode: lanes are shown only near an intersection, so a row of two or more small pictures side by side
     * (a lane row) triggers a full-frame capture plus its node boxes, at most every 5 s.
     */
    private fun captureLaneRow(
        nodes: List<Node>,
        displayId: Int,
    ) {
        val now = SystemClock.uptimeMillis()
        if (displayId < 0 || now - lastLaneCaptureAt < 5_000) return
        val small = nodes.filter { it.className == "ImageView" && it.rect.width() in 8..120 && it.rect.height() in 8..120 }
        val row =
            small
                .groupBy { it.rect.centerY() / 12 }
                .values
                .firstOrNull { group -> group.size >= 2 && group.map { it.rect.left }.distinct().size >= 2 }
                ?: return
        lastLaneCaptureAt = now
        log("NAV-DUMP: lane row candidate: " + row.sortedBy { it.rect.left }.joinToString(" ") { it.rect.toShortString() })
        saveFullFrame(displayId)
    }

    // ---- Maneuver arrow (an image: classified from a screenshot crop, see ManeuverIcon) ----

    private data class ArrowKey(
        val source: String,
        val displayId: Int,
        val afterRoad: String,
        val instruction: String,
        val laneCells: List<Rect>,
    )

    private class ArrowReading(
        val key: ArrowKey,
        val result: ManeuverIcon.Result?,
        val lanes: List<Lane>,
        val at: Long,
    ) {
        fun applyTo(info: NavInfo): NavInfo {
            val withLanes = info.withIcon(result).copy(lanes = lanes)
            if (withLanes.maneuverType != null) return withLanes
            // No arrow picture (Waze near a junction): the highlighted lane arrows give the turn when they agree
            val preferred = lanes.mapNotNull { it.preferredDirection }.distinct()
            return if (preferred.size == 1) withLanes.copy(maneuverType = ManeuverIcon.typeOf(preferred.single())) else withLanes
        }
    }

    private var arrow: ArrowReading? = null

    private var lastInfo: Pair<ArrowKey, NavInfo>? = null

    /** Dump-triggered sample collection (tuning the classifier on real icons) runs until this uptime. */
    @Volatile private var collectUntil = 0L

    /**
     * Fills the maneuver type from the arrow picture when the text did not name it. The classification is cached per
     * maneuver (source + road after it) and refreshed at most every [ARROW_REFRESH_MS]; a new screenshot is requested
     * in the background and the info is re-published when it is classified.
     */
    private fun withArrow(
        info: NavInfo,
        nodes: List<Node>,
        displayId: Int,
    ): NavInfo {
        val arrowNode = arrowNode(info.source, nodes)
        val laneCells =
            when {
                info.source == WAZE -> wazeLaneCells(nodes)
                arrowNode != null && info.source == AMAP -> amapLaneCells(nodes, arrowNode.rect)
                else -> emptyList()
            }
        val key = ArrowKey(info.source, displayId, info.afterRoad, info.instruction, laneCells)
        val description = arrowNode?.description?.takeUnless { it == info.afterRoad }
        val described = info.copy(maneuverType = description?.let(NavText::maneuver) ?: info.maneuverType)
        lastInfo = key to described
        val cached = arrow?.takeIf { it.key == key }
        val now = SystemClock.uptimeMillis()
        val refresh = if (laneCells.isEmpty()) ARROW_REFRESH_MS else LANE_REFRESH_MS
        if (displayId >= 0 && (cached == null || now - cached.at >= refresh)) {
            // Waze shows the lane row instead of its arrow near a junction: capture for the lanes alone then
            val target = arrowNode?.rect ?: laneCells.firstOrNull()
            target?.let { requestArrow(key, if (arrowNode != null) it else Rect()) }
        }
        val reading = cached?.applyTo(described) ?: return described
        return if (now - cached.at < LANE_STALE_MS) reading else reading.copy(lanes = emptyList())
    }

    /**
     * Amap puts equal-sized lane cells below its main arrow and separates each cell with a thin ImageView.
     * The separator check avoids confusing tool buttons and map markers for a lane row.
     */
    private fun amapLaneCells(
        nodes: List<Node>,
        maneuver: Rect,
    ): List<Rect> {
        val images = nodes.filter { it.className == "ImageView" }.map { it.rect }.distinct()
        val groups =
            images
                .filter { it.top >= maneuver.bottom && it.height() in 18..180 && it.width() * 2 in it.height()..it.height() * 2 }
                .groupBy { Triple(it.top, it.bottom, it.width()) }
        for (group in groups.values) {
            if (group.size !in 2..12) continue
            val cells = group.sortedBy { it.left }
            val separated =
                cells.zipWithNext().all { (left, right) ->
                    images.any { mark ->
                        mark.left in left.right until right.left &&
                            mark.right <= right.left &&
                            mark.width() in 1..maxOf(2, left.height() / 16) &&
                            mark.top >= left.top &&
                            mark.bottom <= left.bottom &&
                            mark.height() >= left.height() / 2
                    }
                }
            if (separated) return cells
        }
        // A single-lane row has no separator to prove it is a lane row (Amap 2026-11-21: the whole row was dropped).
        // Accept one cell only directly under the maneuver arrow with no other candidate there; LaneIcon still has to
        // find a stem, a head and a recommended direction before anything is sent.
        val single =
            groups.values
                .filter { it.size == 1 }
                .map { it.first() }
                .filter { it.top - maneuver.bottom <= maneuver.height() }
        return single.takeIf { it.size == 1 }.orEmpty()
    }

    /** Waze lane row: one picture per lane inside `laneGuidanceView` (recommended lanes drawn white, others grey). */
    private fun wazeLaneCells(nodes: List<Node>): List<Rect> {
        val row = nodes.firstOrNull { it.viewId == "com.waze:id/laneGuidanceView" }?.rect ?: return emptyList()
        return nodes
            .filter { it.className == "ImageView" && it.viewId == null && row.contains(it.rect) && it.rect.height() >= 18 }
            .map { it.rect }
            .distinct()
            .sortedBy { it.left }
            .takeIf { it.size in 1..12 }
            .orEmpty()
    }

    /** The arrow = the picture closest to the distance label of the maneuver card. */
    private fun arrowNode(
        source: String,
        nodes: List<Node>,
    ): Node? {
        val anchor =
            if (source == WAZE) {
                nodes.firstOrNull { it.viewId == "com.waze:id/navBarDistance" }
            } else {
                val height = nodes.maxOfOrNull { it.rect.bottom } ?: return null
                nodes.firstOrNull { n ->
                    n.rect.bottom < height / 3 && n.text?.trim()?.matches(Regex("""\d+(?:[.,]\d+)?""")) == true
                }
            } ?: return null
        val a = anchor.rect
        return nodes
            .asSequence()
            .filter { it.className == "ImageView" }
            .filter { node ->
                val r = node.rect
                (source != AMAP || (r.right <= a.left + 2 && r.centerY() in a.top..a.bottom)) &&
                    r.width() in MIN_ARROW_PX..MAX_ARROW_PX &&
                    r.height() in MIN_ARROW_PX..MAX_ARROW_PX &&
                    r.width() < r.height() * 2 &&
                    r.height() < r.width() * 2
            }.map { node ->
                val r = node.rect
                node to
                    kotlin.math.hypot((r.exactCenterX() - a.exactCenterX()).toDouble(), (r.exactCenterY() - a.exactCenterY()).toDouble())
            }.filter { (node, d) -> d < maxOf(a.height(), node.rect.height()) * 4 }
            .minByOrNull { it.second }
            ?.first
    }

    private fun requestArrow(
        key: ArrowKey,
        rect: android.graphics.Rect,
    ) {
        capture(key.displayId, listOf(rect) + key.laneCells) { region ->
            val reading =
                region?.let { runCatching { classifyRegion(it, rect, key) }.getOrNull() }
                    ?: ArrowReading(key, null, emptyList(), SystemClock.uptimeMillis())
            arrow = reading
            // Re-publish the latest reading with the arrow applied
            val info = lastInfo?.takeIf { it.first == key }?.second ?: return@capture
            val selectedMap = VirtualLauncher.activeMapPackage
            val mapDisplay = VirtualLauncher.mapDisplayId
            if (CarRouteGuidance.isWanted &&
                (selectedMap == null || selectedMap == key.source) &&
                (mapDisplay < 0 || mapDisplay == key.displayId)
            ) {
                CarRouteGuidance.publish(info.source, reading.applyTo(info))
            }
        }
    }

    private var screenshotFailureLogged = false

    /** Pixels of one rectangle of a display, in display coordinates (top-left origin), ARGB rows from the top. */
    private class Region(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    ) {
        fun contains(r: Rect): Boolean = r.left >= left && r.top >= top && r.right <= left + width && r.bottom <= top + height

        fun pixelsAt(r: Rect): IntArray =
            IntArray(r.width() * r.height()) { i ->
                pixels[(r.top - top + i / r.width()) * width + (r.left - left + i % r.width())]
            }

        fun pixel(
            x: Int,
            y: Int,
        ): Int = pixels[(y - top).coerceIn(0, height - 1) * width + (x - left).coerceIn(0, width - 1)]

        fun bitmap(r: Rect): Bitmap = Bitmap.createBitmap(pixelsAt(r), r.width(), r.height(), Bitmap.Config.ARGB_8888)

        /** Sampled content fingerprint: an unchanged arrow / lane row is not analyzed again. */
        fun fingerprint(): Int {
            var h = 17
            val step = maxOf(1, pixels.size / 1024)
            var i = 0
            while (i < pixels.size) {
                h = h * 31 + (pixels[i] and 0xF0F0F0)
                i += step
            }
            return h
        }
    }

    private var capturePending = false
    private var lastCaptureAt = 0L

    /**
     * Pixels covering [rects] on [displayId]. The car's map display is read straight from CarDeck's compositor
     * (only this rectangle, no system screenshot, no rate limit beyond [COMPOSER_READ_INTERVAL_MS]); a map on the
     * phone screen falls back to the accessibility screenshot of the whole display (system limit about 1 per s).
     * [onResult] runs on the worker thread.
     */
    private fun capture(
        displayId: Int,
        rects: List<Rect>,
        onResult: (Region?) -> Unit,
    ): Boolean {
        val valid = rects.filterNot { it.isEmpty }
        if (valid.isEmpty()) return false
        val union = Rect(valid.first()).apply { valid.drop(1).forEach { union(it) } }
        val now = SystemClock.uptimeMillis()
        val fromComposer = displayId == VirtualLauncher.mapDisplayId
        val interval = if (fromComposer) COMPOSER_READ_INTERVAL_MS else SCREENSHOT_INTERVAL_MS
        if (capturePending || now - lastCaptureAt < interval) return false
        capturePending = true
        lastCaptureAt = now
        if (fromComposer &&
            VirtualLauncher.requestMapRegion(union) { read, pixels ->
                worker.post {
                    capturePending = false
                    onResult(pixels?.let { Region(read.left, read.top, read.width(), read.height(), it) })
                }
            }
        ) {
            return true
        }
        takeScreenshot(
            displayId,
            { command -> worker.post(command) },
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    capturePending = false
                    onResult(runCatching { regionOf(screenshot, union) }.getOrNull())
                }

                override fun onFailure(errorCode: Int) {
                    capturePending = false
                    if (!screenshotFailureLogged) {
                        screenshotFailureLogged = true
                        log("NAV-A11Y: screenshot failed on display $displayId (error $errorCode) - turn type / lanes stay unknown")
                    }
                    onResult(null)
                }
            },
        )
        return true
    }

    private fun regionOf(
        screenshot: ScreenshotResult,
        area: Rect,
    ): Region? {
        val buffer = screenshot.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            val full =
                try {
                    hardware.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hardware.recycle()
                } ?: return null
            try {
                val r = Rect(area)
                if (!r.intersect(0, 0, full.width, full.height)) return null
                val pixels = IntArray(r.width() * r.height())
                full.getPixels(pixels, 0, r.width(), r.left, r.top, r.width(), r.height())
                return Region(r.left, r.top, r.width(), r.height(), pixels)
            } finally {
                full.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    private var arrowFingerprint = 0

    private fun classifyRegion(
        region: Region,
        rect: Rect,
        key: ArrowKey,
    ): ArrowReading {
        val fingerprint = region.fingerprint()
        val previous = arrow
        if (previous != null && previous.key == key && fingerprint == arrowFingerprint) {
            return ArrowReading(key, previous.result, previous.lanes, SystemClock.uptimeMillis())
        }
        arrowFingerprint = fingerprint
        val arrowRect = Rect(rect)
        val result =
            if (!arrowRect.isEmpty && region.contains(arrowRect) && arrowRect.width() >= 8 && arrowRect.height() >= 8) {
                ManeuverIcon.classify(region.pixelsAt(arrowRect), arrowRect.width(), arrowRect.height()).also { classified ->
                    if (SystemClock.uptimeMillis() < collectUntil) {
                        val crop = region.bitmap(arrowRect)
                        try {
                            saveSample(crop, classified, key.source)
                        } finally {
                            crop.recycle()
                        }
                    }
                }
            } else {
                null
            }
        return ArrowReading(key, result, readLaneRow(region, key.laneCells), SystemClock.uptimeMillis())
    }

    private fun readLaneRow(
        region: Region,
        cells: List<Rect>,
    ): List<Lane> {
        val collecting = SystemClock.uptimeMillis() < collectUntil
        val lanes =
            cells.map { cell ->
                if (!region.contains(cell)) return emptyList()
                LaneIcon.read(region.pixelsAt(cell), cell.width(), cell.height()).also { lane ->
                    // Tuning samples for combined lane icons
                    if (collecting) saveLaneSample(region, cell, lane)
                } ?: unreadLane
            }
        return lanes.takeIf { row -> row.any { it.preferredDirection != null } }.orEmpty()
    }

    private fun saveLaneSample(
        region: Region,
        cell: Rect,
        lane: Lane?,
    ) {
        val dir = getExternalFilesDir("nav-icons") ?: return
        dir.mkdirs()
        val crop = region.bitmap(cell)
        try {
            val label = lane?.let { "dirs${it.directions.joinToString("_")}_pref${it.preferredDirection ?: "none"}" } ?: "unread"
            java.io
                .File(dir, "${System.currentTimeMillis()}_lane_$label.png")
                .outputStream()
                .use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            crop.recycle()
        }
    }

    /**
     * A lane cell that could not be read is still sent, as "straight, not recommended": dropping it made the car
     * show fewer lanes than the road has (Amap bus lanes, 2026-11-21), so the driver could not match lanes to the
     * road. Straight is what a lane marked only with a bus sign almost always allows.
     */
    private val unreadLane = Lane(listOf(0), null)

    /** Tuning samples: arrow crops with the classification in the file name, in the app's own external files. */
    private fun saveSample(
        crop: android.graphics.Bitmap,
        result: ManeuverIcon.Result?,
        source: String,
    ) {
        val dir = getExternalFilesDir("nav-icons") ?: return
        dir.mkdirs()
        val name =
            "${System.currentTimeMillis()}_${source.substringAfterLast('.')}_" +
                "type${result?.type ?: "none"}_angle${result?.angle ?: "na"}_j${result?.junction ?: "na"}.png"
        runCatching {
            java.io
                .File(dir, name)
                .outputStream()
                .use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            log("NAV-DUMP: arrow sample ${dir.absolutePath}/$name")
        }
    }

    /** Waze has stable view IDs for its maneuver card and trip summary (checked on Waze 2026 builds). */
    private fun wazeInfo(nodes: List<Node>): NavInfo? {
        fun byId(id: String) =
            nodes
                .firstOrNull { it.viewId == "com.waze:id/$id" }
                ?.text
                ?.trim()
                .orEmpty()
        val distance = NavText.distance(byId("navBarDistance")) ?: return null
        val street = byId("navBarStreetLine")
        // Current road: the map label Waze tags INSIGHT_TITLE ("SR50 - Spencer St")
        val current =
            nodes
                .firstOrNull { it.description == "INSIGHT_TITLE" }
                ?.text
                ?.trim()
                .orEmpty()
        val toward = byId("navBarTowardStreetLine").trim('(', ')', ' ')
        val now = System.currentTimeMillis()
        return NavInfo(
            source = WAZE,
            toManeuver = distance,
            instruction = listOf(street, toward).filter { it.isNotEmpty() }.joinToString(" "),
            // The arrow is an image without a description; the toward line sometimes names the maneuver
            maneuverType = NavText.maneuver(toward),
            afterRoad = street,
            currentRoad = current,
            remaining = NavText.distance(byId("lblDistanceToDestination")),
            timeRemainingSeconds = NavText.duration(byId("lblTimeToDestination")),
            etaEpochSeconds = NavText.clock(byId("lblArrivalTime"), now),
        )
    }

    private fun navRoots(packages: Set<String> = PACKAGES): List<AccessibilityNodeInfo> {
        val all = windowsOnAllDisplays
        val roots = ArrayList<AccessibilityNodeInfo>()
        for (i in 0 until all.size()) {
            for (window in all.valueAt(i)) {
                val root = window.root ?: continue
                if (root.packageName?.toString() in packages) roots += root
            }
        }
        return roots
    }

    private var lastGoogleSampleAt = 0L
    private var lastGoogleLanesAt = 0L
    private var googleLanesShown = false

    /**
     * Google Maps lanes: its notification has none, and on screen the row is one custom view without children or
     * description (`lane_guidance_list` under the turn card), so it is cut out of a screenshot, split per lane and
     * read with [LaneIcon]. The strip only counts when its background matches the turn card's color: without lanes
     * that area shows the map, which the splitter would otherwise slice into fake lanes.
     */
    private fun readGoogleLanes() {
        if (!CarRouteGuidance.isWanted) return
        val root = navRoots(setOf(GOOGLE_MAPS)).firstOrNull() ?: return
        val displayId = root.window?.displayId ?: return
        val nodes = ArrayList<Node>()
        collect(root, nodes, 0, everything = true)
        val strip = nodes.firstOrNull { it.viewId == "com.google.android.apps.maps:id/lane_guidance_list" }?.rect
        val card = nodes.firstOrNull { it.viewId == "com.google.android.apps.maps:id/step_instruction_container" }?.rect
        if (strip == null || card == null || strip.width() < 40 || strip.height() < 18) {
            if (googleLanesShown) {
                googleLanesShown = false
                CarRouteGuidance.updateLanes(GOOGLE_MAPS, emptyList())
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastGoogleLanesAt < GOOGLE_LANES_MS) return
        // The strip plus one pixel inside the turn card (its background color decides whether the strip is real)
        val cardPoint = Rect(card.right - 13, card.top + 11, card.right - 11, card.top + 13)
        val started =
            capture(displayId, listOf(strip, cardPoint)) { region ->
                val lanes = region?.let { runCatching { googleLanesFrom(it, strip, cardPoint) }.getOrNull() }.orEmpty()
                googleLanesShown = lanes.isNotEmpty()
                CarRouteGuidance.updateLanes(GOOGLE_MAPS, lanes)
            }
        if (started) lastGoogleLanesAt = now
    }

    private var googleFingerprint = 0
    private var googleLanes: List<Lane> = emptyList()

    private fun googleLanesFrom(
        region: Region,
        strip: Rect,
        cardPoint: Rect,
    ): List<Lane> {
        val fingerprint = region.fingerprint()
        if (fingerprint == googleFingerprint) return googleLanes
        googleFingerprint = fingerprint
        val area = Rect(strip)
        if (!area.intersect(region.left, region.top, region.left + region.width, region.top + region.height)) return emptyList()
        val cardPixel = region.pixel(cardPoint.centerX(), cardPoint.centerY())
        val pixels = region.pixelsAt(area)
        if (!sameColor(median(pixels), cardPixel)) return emptyList<Lane>().also { googleLanes = it }
        val collecting = SystemClock.uptimeMillis() < collectUntil
        return LaneIcon
            .splitStrip(pixels, area.width(), area.height())
            .map { columns ->
                val column =
                    Rect(
                        (area.left + columns.first - CELL_MARGIN).coerceAtLeast(area.left),
                        area.top,
                        (area.left + columns.last + 1 + CELL_MARGIN).coerceAtMost(area.right),
                        area.bottom,
                    )
                // Crop to the arrows' rows: LaneIcon's proportions assume arrows that fill the cell
                val rows = LaneIcon.glyphRows(region.pixelsAt(column), column.width(), column.height())
                val cell = Rect(column.left, column.top + rows.first, column.right, column.top + rows.last + 1)
                LaneIcon.read(region.pixelsAt(cell), cell.width(), cell.height()).also { lane ->
                    if (collecting) saveLaneSample(region, cell, lane)
                } ?: unreadLane
            }.takeIf { row -> row.any { it.preferredDirection != null } }
            .orEmpty()
            .also { googleLanes = it }
    }

    private fun median(pixels: IntArray): Int {
        val step = maxOf(1, pixels.size / 400)
        val sample = (pixels.indices step step).map { pixels[it] }

        fun channel(shift: Int) = sample.map { (it shr shift) and 255 }.sorted()[sample.size / 2]
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun sameColor(
        a: Int,
        b: Int,
    ): Boolean =
        kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
            kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
            kotlin.math.abs((a and 255) - (b and 255)) <= 45

    /**
     * Collection mode only: Google Maps' guidance comes from its notification, which has no lane pictures. To learn
     * how its screen exposes lanes, a lane-row-looking set of small pictures on its window triggers a full node log
     * plus a full frame (at most every 5 s). Nothing from this window is published yet.
     */
    private fun sampleGoogleMaps() {
        val now = SystemClock.uptimeMillis()
        if (now >= collectUntil || now - lastGoogleSampleAt < 5_000) return
        for (root in navRoots(setOf(GOOGLE_MAPS))) {
            val displayId = root.window?.displayId ?: continue
            val nodes = ArrayList<Node>()
            collect(root, nodes, 0, everything = true)
            val small = nodes.filter { it.className == "ImageView" && it.rect.width() in 8..160 && it.rect.height() in 8..160 }
            val laneLike =
                small.groupBy { it.rect.centerY() / 12 }.values.any { row -> row.map { it.rect.left }.distinct().size >= 2 } ||
                    nodes.any { n ->
                        listOf(n.text, n.description).any {
                            it?.contains("車道") == true ||
                                it?.contains("车道") == true ||
                                it?.contains("lane", true) == true
                        }
                    }
            if (!laneLike) continue
            lastGoogleSampleAt = now
            log("NAV-DUMP: Google Maps lane sample display=$displayId nodes=${nodes.size}")
            nodes
                .filter { it.text != null || it.description != null || it.className == "ImageView" || it.viewId != null }
                .forEach { n ->
                    log(
                        "NAV-DUMP:   G ${n.className} ${n.viewId ?: "-"} text=${redact(n.text?.take(120))} " +
                            "desc=${redact(n.description?.take(120))} ${n.rect.toShortString()}",
                    )
                }
            saveFullFrame(displayId)
        }
    }

    private class Node(
        val text: String?,
        val description: String?,
        val viewId: String?,
        val rect: android.graphics.Rect,
        val className: String?,
    )

    /**
     * Dump / sample logs only: another app's screen can show the user's account (Google Maps' account disc reads
     * "signed in as <name> <email>"), and exported logs get shared, so email addresses are masked.
     */
    private fun redact(value: String?): String? = value?.replace(EMAIL, "<email>")

    private fun screenTexts(nodes: List<Node>): List<ScreenText> =
        nodes.mapNotNull { n -> n.text?.let { ScreenText(it, n.rect.left, n.rect.top, n.rect.right, n.rect.bottom) } }

    /** [everything] (dump only): also nodes without text/ID, e.g. maneuver-arrow images, with their class names. */
    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableList<Node>,
        depth: Int,
        everything: Boolean = false,
    ) {
        if (depth > MAX_DEPTH || out.size > MAX_NODES) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val description = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val className = node.className?.toString()?.substringAfterLast('.')
        if (everything || text != null || description != null || node.viewIdResourceName != null || className == "ImageView") {
            val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            out += Node(text, description, node.viewIdResourceName, rect, className)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1, everything)
        }
    }

    /** adb-triggered (DUMP-permission receiver): writes the nav apps' visible texts to the log to map new layouts. */
    private fun dumpToLog() {
        // Save the arrow crops seen in the next 10 minutes (run a simulated navigation to collect many maneuvers)
        collectUntil = SystemClock.uptimeMillis() + COLLECT_MS
        arrow = null
        log("NAV-DUMP: collecting arrow samples for ${COLLECT_MS / 60_000} min into ${getExternalFilesDir("nav-icons")}")
        val roots = navRoots(PACKAGES + GOOGLE_MAPS)
        log("NAV-DUMP: ${roots.size} navigation window(s)")
        for (root in roots) {
            val nodes = ArrayList<Node>()
            collect(root, nodes, 0, everything = true)
            log("NAV-DUMP: ${root.packageName} display=${root.window?.displayId} nodes=${nodes.size}")
            nodes.forEach { n ->
                log(
                    "NAV-DUMP:   ${n.className} ${n.viewId ?: "-"} text=${redact(n.text?.take(300))} " +
                        "desc=${redact(n.description?.take(80))} ${n.rect.toShortString()}",
                )
            }
            // What the parser makes of this screen right now
            val pkg = root.packageName?.toString().orEmpty()
            val parsed =
                if (pkg == WAZE) wazeInfo(nodes) else NavText.fromAmapScreen(pkg, screenTexts(nodes), System.currentTimeMillis())
            log("NAV-DUMP: parsed -> $parsed")
            root.window?.displayId?.let { saveFullFrame(it) }
        }
    }

    /** Dump only: the whole map display as PNG, to cut out lane / arrow icons offline with the node boxes above. */
    private fun saveFullFrame(
        displayId: Int,
        retry: Boolean = true,
    ) {
        takeScreenshot(
            displayId,
            { command -> worker.post(command) },
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    runCatching {
                        val hardware =
                            android.graphics.Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        val frame = hardware?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        val dir = getExternalFilesDir("nav-icons") ?: return@runCatching
                        dir.mkdirs()
                        val file = java.io.File(dir, "frame_${System.currentTimeMillis()}_display$displayId.png")
                        file.outputStream().use { frame?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        log("NAV-DUMP: full frame ${file.absolutePath}")
                    }.onFailure { log("NAV-DUMP: full frame failed - ${it.message}") }
                }

                override fun onFailure(errorCode: Int) {
                    // 3 = interval too short (it collided with the arrow capture): try once more a little later
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && retry) {
                        worker.postDelayed({ saveFullFrame(displayId, retry = false) }, 1_200)
                    } else {
                        log("NAV-DUMP: full frame screenshot failed (error $errorCode)")
                    }
                }
            },
        )
    }

    companion object {
        /** A head unit subscribed to route guidance: start reading the map screen periodically. */
        fun onRouteGuidanceWanted() {
            instance?.startPolling()
        }

        const val WAZE = "com.waze"
        const val AMAP = "com.autonavi.minimap"
        val PACKAGES = setOf(WAZE, AMAP)

        private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")

        /** Read for samples only (see [sampleGoogleMaps]); its guidance comes from the notification. */
        const val GOOGLE_MAPS = MapsNotificationSource.PACKAGE

        private const val READ_INTERVAL_MS = 700L
        private const val KEEP_READING_MS = 2_000L
        private const val FAR_POLL_MS = 4_000L
        private const val FAR_FROM_MANEUVER_M = 1_000L

        /** Compositor reads cost a small off-screen draw: a few per second is plenty for arrows and lanes. */
        private const val COMPOSER_READ_INTERVAL_MS = 300L
        private const val GOOGLE_LANES_MS = 2_000L
        private const val CELL_MARGIN = 6
        private const val ARROW_REFRESH_MS = 4_000L
        private const val LANE_REFRESH_MS = 1_100L
        private const val LANE_STALE_MS = 3_000L
        private const val SCREENSHOT_INTERVAL_MS = 1_100L
        private const val MIN_ARROW_PX = 20
        private const val MAX_ARROW_PX = 260
        private const val COLLECT_MS = 10 * 60_000L
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 400

        @Volatile
        private var instance: NavAccessibilityService? = null

        @Volatile
        var log: (String) -> Unit = {}

        fun component(context: Context) = ComponentName(context, NavAccessibilityService::class.java)

        /** Whether the user enabled the service in system accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled =
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?: return false
            val me = component(context)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Dumps the navigation windows to the log; false when the service is not running. */
        fun dump(): Boolean {
            val service = instance ?: return false
            service.worker.post { runCatching { service.dumpToLog() }.onFailure { log("NAV-DUMP: failed - ${it.message}") } }
            return true
        }
    }
}

package io.github.ezn24.droidcar.nav

import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate
import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate.Maneuver
import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate.Route
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds turn-by-turn data read from the phone's navigation apps to head units that subscribed with iAP2 0x5200
 * (instrument cluster / HUD), one subscription per link (Bluetooth control session, Wi-Fi tunnel).
 *
 * Sources ([NavAccessibilityService] for Waze / Amap screen text, [MapsNotificationSource] for the Google Maps
 * notification) call [publish]. A source that stops publishing for [STALE_MS] counts as "navigation ended" and the
 * head unit gets RouteGuidanceState = no route. Updates are sent on change, at most once per [MIN_INTERVAL_MS]
 * unless the maneuver itself changed. Only active while a head unit is subscribed: [isWanted] lets sources skip
 * all reading otherwise.
 */
object CarRouteGuidance {
    private class Subscription(
        val name: String,
        val components: List<Int>,
        val send: (ByteArray) -> Boolean,
    ) {
        var lastRoute: Route? = null
        var lastSentAt = 0L
        var lastManeuver: Maneuver? = null
        var lastLanes = emptyList<RouteGuidanceUpdate.Lane>()
        var laneIndex = 0
    }

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val scheduled = AtomicBoolean(false)
    private val executor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "car-route-guidance").apply { isDaemon = true }
        }

    @Volatile
    var log: (String) -> Unit = {}

    /** Latest reading per source (package), with its uptime. */
    private class SourceState(
        val info: NavInfo,
        val at: Long,
    )

    private val sources = ConcurrentHashMap<String, SourceState>()

    @Volatile
    private var chosenSource: String? = null

    // Maneuver identity -> index (a new index tells the head unit "next maneuver", the same index refreshes it)
    private var previousManeuverInfo: NavInfo? = null
    private var maneuverIndex = 0

    @Volatile
    private var enabled = false

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) sources.clear()
        schedule(0)
    }

    /** Whether navigation is enabled and any head unit wants guidance. */
    val isWanted: Boolean get() = enabled && (subscriptions.isNotEmpty() || listeners.isNotEmpty())

    /** Non-iAP2 consumers (CarLife HUD): get every reading, keep the sources reading while registered. */
    private val listeners = ConcurrentHashMap<String, (NavInfo?) -> Unit>()

    fun listen(
        name: String,
        listener: (NavInfo?) -> Unit,
    ) {
        listeners[name] = listener
        NavAccessibilityService.onRouteGuidanceWanted()
        log("NAV-CAR: $name listening for route guidance")
        schedule(0)
    }

    fun unlisten(name: String) {
        if (listeners.remove(name) != null) log("NAV-CAR: $name stopped listening for route guidance")
    }

    /**
     * A source read new navigation info; null = it no longer shows navigation. Fields a reading lacks are carried
     * over from the previous one of the same maneuver: the Google Maps notification alternates between
     * "140 公尺 / 向左轉進入X" and "向左轉進入X / 04:13 抵達", and each half alone would blank the other's fields.
     */

    /**
     * [untilRemoved]: the source reports its own end (a notification is posted only when something changes, so
     * silence is not "navigation ended"; Google Maps went quiet for 10+ s between maneuvers in the 2026-11-21 drive).
     */
    @Synchronized
    fun publish(
        source: String,
        info: NavInfo?,
        untilRemoved: Boolean = false,
    ) {
        if (!enabled) return
        if (info == null) {
            persistentSources.remove(source)
            if (sources.remove(source) == null) return
        } else {
            if (untilRemoved) persistentSources += source
            sources[source] = SourceState(merge(sources[source]?.info, info), now())
        }
        schedule(0)
    }

    /** Sources exempt from [STALE_MS]: they publish null themselves when navigation ends. */
    private val persistentSources =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /**
     * Only the selected map can provide instructions while the car's map pane is open. With no selected map,
     * prefer navigation read from a visible window. Sources quiet for [STALE_MS] are dropped.
     */
    private fun current(): NavInfo? {
        if (!enabled) return null
        val now = now()
        val stale = sources.filter { (source, state) -> source !in persistentSources && now - state.at > STALE_MS }.keys
        stale.forEach { sources.remove(it) }
        val selectedMap = VirtualLauncher.activeMapPackage
        val pick =
            if (selectedMap != null) {
                sources[selectedMap]
            } else {
                sources.values.maxWithOrNull(
                    compareBy<SourceState>({ if (it.info.source in NavAccessibilityService.PACKAGES) 1 else 0 }, { it.at }),
                )
            }
        val source = pick?.info?.source
        if (source != chosenSource) {
            log(
                if (source == null) {
                    "NAV-CAR: navigation source went quiet - reporting no route"
                } else {
                    "NAV-CAR: reporting navigation from $source"
                },
            )
            chosenSource = source
        }
        val info = pick?.info ?: return null
        // Lanes read from the app's screen (Google Maps: its notification has none) join that source's reading
        val lanes = screenLanes[info.source]?.takeIf { info.lanes.isEmpty() && now - it.at < SCREEN_LANES_TTL_MS }
        return if (lanes != null) info.copy(lanes = lanes.lanes) else info
    }

    private class ScreenLanes(
        val lanes: List<RouteGuidanceUpdate.Lane>,
        val at: Long,
    )

    private val screenLanes = ConcurrentHashMap<String, ScreenLanes>()

    /** Lane row read from [source]'s screen; empty = no lane row shown right now. */
    fun updateLanes(
        source: String,
        lanes: List<RouteGuidanceUpdate.Lane>,
    ) {
        val previous = screenLanes[source]?.lanes.orEmpty()
        if (lanes.isEmpty()) screenLanes.remove(source) else screenLanes[source] = ScreenLanes(lanes, now())
        if (lanes != previous) schedule(0)
    }

    /**
     * Same maneuver = same source and road after it, and no conflicting turn type (a type that arrives later from
     * the arrow picture does not make it a new maneuver; wording and distance vary between readings).
     */
    internal fun sameManeuver(
        a: NavInfo,
        b: NavInfo,
    ): Boolean =
        a.source == b.source &&
            a.afterRoad == b.afterRoad &&
            (a.maneuverType == null || b.maneuverType == null || a.maneuverType == b.maneuverType)

    internal fun merge(
        previous: NavInfo?,
        next: NavInfo,
    ): NavInfo {
        if (previous == null || previous.source != next.source) return next
        val same = sameManeuver(previous, next) || (next.maneuverType == null && next.afterRoad.isEmpty())
        val base = if (next.maneuverType == null && next.afterRoad.isEmpty()) previous else next
        return base.copy(
            maneuverType = next.maneuverType ?: previous.maneuverType.takeIf { same },
            junction = next.junction ?: previous.junction.takeIf { same },
            exitAngle = next.exitAngle ?: previous.exitAngle.takeIf { same },
            junctionAngles = next.junctionAngles.ifEmpty { previous.junctionAngles.takeIf { same }.orEmpty() },
            lanes = next.lanes,
            toManeuver = next.toManeuver ?: previous.toManeuver.takeIf { same },
            instruction = next.instruction.ifEmpty { previous.instruction.takeIf { same }.orEmpty() },
            remaining = next.remaining ?: previous.remaining,
            timeRemainingSeconds = next.timeRemainingSeconds ?: previous.timeRemainingSeconds,
            etaEpochSeconds = next.etaEpochSeconds ?: previous.etaEpochSeconds,
        )
    }

    /** 0x5200 on link [name]: returns the frames that answer it now (current route, or "no route"). */
    internal fun subscribe(
        name: String,
        requestBody: ByteArray,
        send: (ByteArray) -> Boolean,
    ): List<ByteArray> {
        val subscription = Subscription(name, RouteGuidanceUpdate.parseComponents(requestBody), send)
        subscriptions[name] = subscription
        // Screen sources may never raise an accessibility event (Waze's map is a SurfaceView): start polling them
        NavAccessibilityService.onRouteGuidanceWanted()
        val frames = framesFor(subscription, force = true)
        log(
            "NAV-CAR: $name subscribed to route guidance (components=${subscription.components}) -> " +
                (current()?.let { "navigating (${it.source})" } ?: "no route"),
        )
        return frames
    }

    fun unsubscribe(
        name: String,
        reason: String,
    ) {
        if (subscriptions.remove(name) != null) log("NAV-CAR: $name route guidance subscription ended ($reason)")
    }

    private fun schedule(delayMs: Long) {
        if ((subscriptions.isEmpty() && listeners.isEmpty()) || !scheduled.compareAndSet(false, true)) return
        executor.schedule({
            scheduled.set(false)
            runCatching { push() }.onFailure { log("NAV-CAR: update failed - ${it.message}") }
        }, delayMs.coerceAtLeast(DEBOUNCE_MS), TimeUnit.MILLISECONDS)
    }

    /** Latest reading for the alternate (cluster) screen's turn card; null = no route. */
    @Volatile
    var onInfo: ((NavInfo?) -> Unit)? = null

    private fun push() {
        val info = current()
        runCatching { onInfo?.invoke(info) }
        listeners.values.forEach { l -> runCatching { l(info) } }
        val navigating = info != null
        for (subscription in subscriptions.values) {
            for (frame in framesFor(subscription, force = false)) {
                if (!subscription.send(frame)) {
                    unsubscribe(subscription.name, "send failed")
                    break
                }
            }
        }
        // Keep checking for staleness and throttled changes while navigation is on
        if (navigating) schedule(MIN_INTERVAL_MS)
    }

    @Synchronized
    private fun framesFor(
        subscription: Subscription,
        force: Boolean,
    ): List<ByteArray> {
        val info = current()
        val now = now()
        if (info == null) {
            val route =
                Route(
                    state = RouteGuidanceUpdate.ROUTE_NO_ROUTE,
                    laneGuidanceShowing = false.takeIf { subscription.lastRoute?.laneGuidanceShowing != null },
                )
            if (!force && subscription.lastRoute == route) return emptyList()
            subscription.lastRoute = route
            subscription.lastSentAt = now
            subscription.lastManeuver = null
            subscription.lastLanes = emptyList()
            previousManeuverInfo = null
            return listOf(RouteGuidanceUpdate.routeFrame(route, subscription.components))
        }
        val maneuver = maneuverOf(info)
        val previous = subscription.lastManeuver
        val newManeuver = previous?.index != maneuver.index
        val lanesChanged = info.lanes != subscription.lastLanes
        val hasLanes = info.lanes.isNotEmpty()
        val sendLanes = hasLanes && (force || newManeuver || lanesChanged)
        if (sendLanes) subscription.laneIndex = (subscription.laneIndex + 1) and 0x7FFF
        val route =
            Route(
                state = RouteGuidanceUpdate.ROUTE_SET,
                maneuverState = maneuverStateOf(info),
                currentRoad = info.currentRoad,
                etaEpochSeconds = info.etaEpochSeconds,
                timeRemainingSeconds = info.timeRemainingSeconds,
                remaining = info.remaining,
                toManeuver = info.toManeuver,
                maneuverIndexes = listOf(maneuver.index),
                laneIndex = subscription.laneIndex.takeIf { hasLanes },
                laneGuidanceShowing = hasLanes.takeIf { it || subscription.lastRoute?.laneGuidanceShowing != null },
            )
        val iconChanged =
            previous?.type != maneuver.type ||
                previous?.junction != maneuver.junction ||
                previous?.exitAngle != maneuver.exitAngle ||
                previous?.junctionAngles != maneuver.junctionAngles
        if (!force && !newManeuver && !iconChanged && !lanesChanged) {
            if (route == subscription.lastRoute && maneuver == previous) return emptyList()
            if (now - subscription.lastSentAt < MIN_INTERVAL_MS) return emptyList()
        }
        val frames = ArrayList<ByteArray>(3)
        if (sendLanes) {
            frames += RouteGuidanceUpdate.laneFrame(subscription.laneIndex, info.lanes, subscription.components)
            log("NAV-CAR: ${subscription.name} <- lane guidance #${subscription.laneIndex} (${info.lanes.size} lanes)")
        }
        if (maneuver != previous || force) {
            frames += RouteGuidanceUpdate.maneuverFrame(maneuver, subscription.components, info.toManeuver)
            log(
                "NAV-CAR: ${subscription.name} <- maneuver #${maneuver.index} type=${maneuver.type ?: "unknown"} " +
                    "in ${info.toManeuver?.text ?: "?"} (${info.source})",
            )
        }
        frames += RouteGuidanceUpdate.routeFrame(route, subscription.components)
        subscription.lastRoute = route
        subscription.lastSentAt = now
        subscription.lastManeuver = maneuver
        subscription.lastLanes = info.lanes
        return frames
    }

    private fun maneuverOf(info: NavInfo): Maneuver {
        if (previousManeuverInfo?.let { sameManeuver(it, info) } != true) {
            maneuverIndex = (maneuverIndex + 1) and 0x7FFF
        }
        previousManeuverInfo = info
        return Maneuver(
            index = maneuverIndex,
            description = info.instruction,
            type = info.maneuverType,
            afterRoad = info.afterRoad,
            junction = RouteGuidanceUpdate.junctionFor(info.maneuverType) ?: info.junction,
            exitAngle = info.exitAngle ?: RouteGuidanceUpdate.exitAngleFor(info.maneuverType),
            junctionAngles = info.junctionAngles,
        )
    }

    /** Continue far from the maneuver, prepare when approaching, execute when it is right ahead. */
    private fun maneuverStateOf(info: NavInfo): Int {
        val meters = info.toManeuver?.meters ?: return RouteGuidanceUpdate.MANEUVER_CONTINUE
        return when {
            meters <= EXECUTE_METERS -> RouteGuidanceUpdate.MANEUVER_EXECUTE
            meters <= PREPARE_METERS -> RouteGuidanceUpdate.MANEUVER_PREPARE
            else -> RouteGuidanceUpdate.MANEUVER_CONTINUE
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    private const val DEBOUNCE_MS = 200L
    private const val MIN_INTERVAL_MS = 1_000L
    private const val STALE_MS = 10_000L

    /** Screen lanes older than this are dropped (the reader refreshes them every 2 s while they are shown). */
    private const val SCREEN_LANES_TTL_MS = 6_000L
    private const val PREPARE_METERS = 500L
    private const val EXECUTE_METERS = 50L
}

package io.github.ezn24.droidcar.nav

import android.app.Notification
import android.service.notification.StatusBarNotification
import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate

/**
 * Google Maps shows the next maneuver in its ongoing navigation notification (distance + instruction, trip summary
 * in the sub text), so it needs no accessibility access: [io.github.ezn24.droidcar.media.MediaListenerService]
 * forwards its notifications here. Parsing is the shared tolerant [NavText.fromTexts]. (unverified layout: the
 * notification wording differs by Maps version and language; `NAV-DUMP` shows the last raw sample)
 */
object MapsNotificationSource {
    const val PACKAGE = "com.google.android.apps.maps"

    @Volatile
    private var lastKey: String? = null

    /** Last few distinct notification texts, only for the adb-triggered NAV-DUMP (Maps alternates layouts). */
    private val samples = java.util.ArrayDeque<List<String>>()

    fun onPosted(
        context: android.content.Context,
        sbn: StatusBarNotification,
    ) {
        if (sbn.packageName != PACKAGE) return
        val notification = sbn.notification ?: return
        val navigation =
            notification.category == Notification.CATEGORY_NAVIGATION ||
                (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!navigation) return
        val extras = notification.extras
        val texts =
            listOf(
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_INFO_TEXT,
            ).mapNotNull { extras.getCharSequence(it)?.toString()?.takeIf { s -> s.isNotBlank() } }
                .distinct()
        synchronized(samples) {
            if (texts !in samples) {
                samples.addLast(texts)
                while (samples.size > MAX_SAMPLES) samples.removeFirst()
            }
        }
        if (!CarRouteGuidance.isWanted) return
        val info =
            NavText.fromTexts(PACKAGE, texts, System.currentTimeMillis())?.withLiveUpdate(extras)?.let { parsed ->
                if (parsed.maneuverType != null && parsed.maneuverType != RouteGuidanceUpdate.TYPE_U_TURN) return@let parsed
                // The picture may identify the side of a U-turn even when the wording gives only its type.
                val result = runCatching { classifyLargeIcon(context, notification) }.getOrNull()
                parsed.withIcon(result)
            }
        if (info != null) {
            lastKey = sbn.key
            CarRouteGuidance.publish(PACKAGE, info, untilRemoved = true)
        }
    }

    /**
     * Android 16 "live update" fields of Maps' promoted ongoing notification (2026-11-21, Maps on a Samsung S24):
     * `android.shortCriticalText` = distance to the next maneuver ("3.7 公里", also the status-bar chip; the title
     * omits it on "continue on X" legs), Samsung's `ongoingActivityNoti.secondaryInfo` = exit / road after the
     * maneuver ("E4 號出口 Toorak Rd"), and ProgressStyle `progress`/`progressMax` = meters driven / route meters.
     */
    private fun NavInfo.withLiveUpdate(extras: android.os.Bundle): NavInfo {
        val distanceText =
            listOf("android.shortCriticalText", "android.ongoingActivityNoti.primaryInfo", "android.ongoingActivityNoti.chipExpandedText")
                .firstNotNullOfOrNull { key -> extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() } }
        val road =
            extras
                .getCharSequence("android.ongoingActivityNoti.secondaryInfo")
                ?.toString()
                ?.trim()
                .orEmpty()
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val done = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        return NavText.withLiveUpdate(this, distanceText, road, if (max > 0 && done in 0..max) (max - done).toLong() else null)
    }

    private fun classifyLargeIcon(
        context: android.content.Context,
        notification: Notification,
    ): ManeuverIcon.Result? {
        val drawable = notification.getLargeIcon()?.loadDrawable(context) ?: return null
        val size = 96
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(android.graphics.Canvas(bitmap))
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        return ManeuverIcon.classify(pixels, size, size)
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key == lastKey) {
            lastKey = null
            CarRouteGuidance.publish(PACKAGE, null)
        }
    }

    fun dumpToLog(log: (String) -> Unit) {
        val copy = synchronized(samples) { samples.toList() }
        log("NAV-DUMP: Google Maps notification variants: ${copy.size}")
        copy.forEach { sample -> log("NAV-DUMP:   " + sample.joinToString(" | ") { it.take(80) }) }
    }

    private const val MAX_SAMPLES = 6
}

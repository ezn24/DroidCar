package io.github.ezn24.cardeck.neo.call

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher

/**
 * Phone call state for the car's call card, read from call notifications (the notification listener CarDeck already
 * holds). Android 12+ CallStyle notifications from dialers and VoIP apps (WhatsApp etc.) carry the caller and the
 * answer / decline / hang-up PendingIntents, so one path covers cellular and VoIP calls. A call notification without
 * CallStyle still shows the card; its buttons then fall back to Telecom through the Shizuku service.
 */
object CallState {
    enum class Phase { RINGING, ACTIVE }

    data class Call(
        val key: String,
        val pkg: String,
        val caller: String?,
        val phase: Phase,
        /** Wall-clock start of the connected call (the notification's chronometer base), 0 = unknown. */
        val startedAtWallMs: Long,
        val answer: PendingIntent?,
        val decline: PendingIntent?,
        val hangUp: PendingIntent?,
        /** Caller photo: the CallStyle person's icon, else the notification's large icon. Loaded off the main thread. */
        val avatar: android.graphics.drawable.Icon? = null,
        /** The notification's tap target: brings the app's call screen back. */
        val open: PendingIntent? = null,
        /** Full-screen incoming-call screen (WeChat rings with one and no answer/decline actions). */
        val fullScreen: PendingIntent? = null,
        /** Custom notification layout, the only place WeChat puts the caller photo (see [CallAvatar]). */
        val views: android.widget.RemoteViews? = null,
        /** Known only from the app's ringtone, no notification behind it (see [onRingers]). */
        val fromRingtone: Boolean = false,
    )

    private val calls = LinkedHashMap<String, Call>()

    /** The last ringing call per app (until its ongoing notification is adopted), see [adoptOngoing]. */
    private val lastRinging = HashMap<String, Pair<Call, Long>>()

    /** Ongoing notifications adopted as a connected call: key -> wall-clock time first seen. */
    private val adopted = HashMap<String, Long>()

    /** Ongoing foreground-service notifications seen, so an app that starts a call later can have its one adopted. */
    private val ongoing = HashMap<String, StatusBarNotification>()

    /**
     * Apps in a call right now by audio evidence: recording while the system is in call / communication mode. Outgoing
     * calls never ring (WeChat, Telegram 2026-11-21), so this is what adopts their ongoing notification.
     */
    private val inCallApps = HashSet<String>()

    /** Apps currently playing a ringtone, from the Shizuku service (see [onRingers]). */
    private val ringers = HashSet<String>()

    @Volatile
    private var audioManager: android.media.AudioManager? = null

    /** The notification listener's context (for the audio mode). */
    fun attach(context: android.content.Context) {
        audioManager = context.applicationContext.getSystemService(android.media.AudioManager::class.java)
    }

    /**
     * Apps recording right now (CarMicController polls this during a session). In call / communication mode they count as
     * in a call: their ongoing notification becomes the call, and it stays while they remain in the call even if the app
     * briefly removes the notification (WeChat re-posts it). Leaving the call drops the adopted card.
     */
    fun onRecorders(packages: Collection<String>) {
        val mode = audioManager?.mode ?: return
        val inCall = mode == android.media.AudioManager.MODE_IN_COMMUNICATION || mode == android.media.AudioManager.MODE_IN_CALL
        val now = if (inCall) packages.toSet() else emptySet()
        val changed =
            synchronized(calls) {
                if (now == inCallApps) return
                val left = inCallApps - now
                val joined = now - inCallApps
                inCallApps.clear()
                inCallApps.addAll(now)
                for (pkg in left) {
                    val keys = calls.values.filter { it.pkg == pkg && it.key in adopted }.map { it.key }
                    keys.forEach {
                        calls.remove(it)
                        adopted.remove(it)
                    }
                }
                for (pkg in joined) {
                    ongoing.values.filter { it.packageName == pkg }.forEach { sbn ->
                        adoptOngoing(sbn)?.let { calls[sbn.key] = it }
                    }
                }
                left.isNotEmpty() || joined.isNotEmpty()
            }
        if (changed) update()
    }

    /**
     * Apps playing a ringtone right now (CarMicController polls this during a session). WeChat rings without any call
     * notification while its call screen is up on the phone or the app is in the foreground (2026-11-22, 3 of 6 calls), so
     * a ringing app with no call of its own becomes a ringing call without caller details; its buttons open the app's call
     * screen on the car. The ringtone stopping drops it (answered calls are then adopted through [onRecorders]).
     */
    fun onRingers(packages: Collection<String>) {
        val now = packages.toSet()
        val changed =
            synchronized(calls) {
                if (now == ringers) return
                val stopped = ringers - now
                ringers.clear()
                ringers.addAll(now)
                var changed = false
                for (pkg in stopped) {
                    val removed = calls.remove(RINGTONE_KEY_PREFIX + pkg) ?: continue
                    lastRinging[pkg] = removed to System.currentTimeMillis()
                    changed = true
                }
                for (pkg in now) {
                    if (calls.values.any { it.pkg == pkg }) continue
                    calls[RINGTONE_KEY_PREFIX + pkg] =
                        Call(RINGTONE_KEY_PREFIX + pkg, pkg, null, Phase.RINGING, 0L, null, null, null, fromRingtone = true)
                    changed = true
                }
                changed
            }
        if (changed) update()
    }

    /** The call the card shows: a ringing call wins over an ongoing one (call waiting). */
    @Volatile
    var current: Call? = null
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun onPosted(sbn: StatusBarNotification) {
        val call =
            synchronized(calls) {
                if (isOngoingService(sbn)) ongoing[sbn.key] = sbn else ongoing.remove(sbn.key)
                (parse(sbn) ?: adoptOngoing(sbn))?.also {
                    // A real notification for the app replaces its ringtone-only stand-in
                    calls.remove(RINGTONE_KEY_PREFIX + sbn.packageName)
                }
            }
        synchronized(calls) {
            if (call == null) {
                if (calls.remove(sbn.key) == null) return
            } else {
                calls[sbn.key] = call
            }
        }
        update()
    }

    fun onRemoved(sbn: StatusBarNotification) {
        synchronized(calls) {
            ongoing.remove(sbn.key)
            // An adopted call of an app still in a call outlives a removed / re-posted notification
            if (sbn.key in adopted && sbn.packageName in inCallApps) return
            adopted.remove(sbn.key)
            val removed = calls.remove(sbn.key) ?: return
            if (removed.phase == Phase.RINGING) lastRinging[removed.pkg] = removed to System.currentTimeMillis()
        }
        update()
    }

    /**
     * Apps without CallStyle for the connected call (Telegram, 2026-11-21: ringing used CallStyle, the connected call is a
     * plain ongoing foreground-service notification with a single "end call" action): an ongoing foreground notification of
     * the app that was just ringing becomes its connected call. A single action is taken as hang-up; caller and photo
     * carry over from the ringing notification. Must hold the [calls] lock.
     */
    private fun adoptOngoing(sbn: StatusBarNotification): Call? {
        val n = sbn.notification ?: return null
        if (!isOngoingService(sbn)) return null
        val now = System.currentTimeMillis()
        val ringingNow = calls.values.lastOrNull { it.pkg == sbn.packageName && it.phase == Phase.RINGING }
        val recent = lastRinging[sbn.packageName]?.takeIf { now - it.second <= ADOPT_WINDOW_MS }?.first
        val since = adopted[sbn.key]
        val source = ringingNow ?: recent
        if (since == null && source == null && sbn.packageName !in inCallApps) return null
        val started = since ?: now
        adopted[sbn.key] = started
        val actions = n.actions.orEmpty()
        val previous = calls[sbn.key]
        return Call(
            key = sbn.key,
            pkg = sbn.packageName,
            caller = source?.caller ?: previous?.caller ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            phase = Phase.ACTIVE,
            startedAtWallMs = started,
            answer = null,
            decline = null,
            hangUp = actions.singleOrNull()?.actionIntent,
            avatar = source?.avatar ?: previous?.avatar ?: n.getLargeIcon(),
            open = n.contentIntent,
            views = source?.views ?: previous?.views ?: n.customViews(),
        )
    }

    private fun isOngoingService(sbn: StatusBarNotification): Boolean {
        val flags = sbn.notification?.flags ?: return false
        return flags and Notification.FLAG_ONGOING_EVENT != 0 && flags and Notification.FLAG_FOREGROUND_SERVICE != 0
    }

    /** Listener disconnected: nothing is known any more. */
    fun clear() {
        synchronized(calls) {
            calls.clear()
            adopted.clear()
            lastRinging.clear()
            ongoing.clear()
            inCallApps.clear()
            ringers.clear()
        }
        update()
    }

    private fun update() {
        val next =
            synchronized(calls) {
                calls.values.lastOrNull { it.phase == Phase.RINGING } ?: calls.values.lastOrNull()
            }
        val old = current
        current = next
        if (old == next) return
        if (old?.phase != next?.phase || old?.key != next?.key) {
            // Caller names are personal data: the log keeps only the app and the phase
            VirtualLauncher.logSink?.invoke(
                "CALL: " + (
                    next?.let {
                        "${it.phase} pkg=${it.pkg} ringtoneOnly=${it.fromRingtone} " +
                            "callStyle=${it.answer != null || it.hangUp != null} avatar=${iconKind(it.avatar)}"
                    } ?: "none"
                ),
            )
        }
        listeners.forEach { runCatching { it() } }
    }

    internal fun parse(sbn: StatusBarNotification): Call? {
        val n = sbn.notification ?: return null
        val extras = n.extras
        // Notification.CallStyle extras (API 31): callType 1 incoming, 2 ongoing, 3 screening
        val callType = extras.getInt(EXTRA_CALL_TYPE, 0)
        if (callType == 0 && n.category != Notification.CATEGORY_CALL) return null
        val answer = pendingIntent(extras, EXTRA_ANSWER_INTENT)
        val decline = pendingIntent(extras, EXTRA_DECLINE_INTENT)
        val hangUp = pendingIntent(extras, EXTRA_HANG_UP_INTENT)
        val phase =
            when (callType) {
                CALL_TYPE_INCOMING -> Phase.RINGING
                CALL_TYPE_ONGOING, CALL_TYPE_SCREENING -> Phase.ACTIVE
                // Plain call notification: an incoming one comes with a full-screen intent, an ongoing one usually not
                else -> if (answer != null || (n.fullScreenIntent != null && !n.usesChronometer())) Phase.RINGING else Phase.ACTIVE
            }
        val personObject =
            runCatching {
                androidx.core.os.BundleCompat
                    .getParcelable(extras, EXTRA_CALL_PERSON, android.app.Person::class.java)
            }.getOrNull()
        val person = personObject?.name?.toString()
        val caller =
            person?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val started = if (phase == Phase.ACTIVE && n.usesChronometer() && n.`when` > 0) n.`when` else 0L
        val avatar = personObject?.icon ?: n.getLargeIcon()
        return Call(
            sbn.key,
            sbn.packageName,
            caller,
            phase,
            started,
            answer,
            decline,
            hangUp,
            avatar,
            n.contentIntent,
            n.fullScreenIntent,
            n.customViews(),
        )
    }

    /** Icon type name for the log (no content): BITMAP / RESOURCE / DATA / URI / ADAPTIVE_BITMAP / URI_ADAPTIVE_BITMAP. */
    private fun iconKind(icon: android.graphics.drawable.Icon?): String =
        when (icon?.type) {
            null -> "none"
            android.graphics.drawable.Icon.TYPE_BITMAP -> "bitmap"
            android.graphics.drawable.Icon.TYPE_RESOURCE -> "resource"
            android.graphics.drawable.Icon.TYPE_DATA -> "data"
            android.graphics.drawable.Icon.TYPE_URI -> "uri"
            android.graphics.drawable.Icon.TYPE_ADAPTIVE_BITMAP -> "adaptive"
            android.graphics.drawable.Icon.TYPE_URI_ADAPTIVE_BITMAP -> "uri-adaptive"
            else -> "type${icon.type}"
        }

    @Suppress("DEPRECATION")
    private fun Notification.customViews(): android.widget.RemoteViews? = headsUpContentView ?: bigContentView ?: contentView

    private fun Notification.usesChronometer(): Boolean = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)

    private fun pendingIntent(
        extras: android.os.Bundle,
        key: String,
    ): PendingIntent? =
        runCatching {
            androidx.core.os.BundleCompat
                .getParcelable(extras, key, PendingIntent::class.java)
        }.getOrNull()

    // Notification.EXTRA_CALL_TYPE & co. are hidden constants; their values are stable since API 31
    private const val ADOPT_WINDOW_MS = 20_000L
    private const val RINGTONE_KEY_PREFIX = "ringtone:"
    private const val EXTRA_CALL_TYPE = "android.callType"
    private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
    private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
    private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
    private const val EXTRA_CALL_PERSON = "android.callPerson"
    private const val CALL_TYPE_INCOMING = 1
    private const val CALL_TYPE_ONGOING = 2
    private const val CALL_TYPE_SCREENING = 3
}

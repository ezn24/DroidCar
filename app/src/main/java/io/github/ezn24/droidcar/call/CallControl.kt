package io.github.ezn24.droidcar.call

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.os.Build
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import io.github.ezn24.droidcar.shizuku.CarInputClient
import io.github.ezn24.droidcar.util.Bg

/** Car-side call buttons. CallStyle intents first; without them Telecom via the Shizuku service (shell holds ANSWER_PHONE_CALLS). */
object CallControl {
    /** Mic muted from the car card: undone when the call ends so the phone mic never stays muted. */
    @Volatile
    private var mutedByUs = false

    fun answer(context: Context) {
        val call = CallState.current ?: return
        if (!send(context, call.answer)) {
            // No answer action (WeChat): show the app's own incoming-call screen in the media pane to answer there
            if (openCallScreen(context, call)) return
            telecom(ACTION_ANSWER)
        }
        // The app may open its call screen itself after answering (then the display option above does not apply):
        // check twice and send it back to the phone if it landed on the car
        Bg.run("call-screen") {
            for (delayMs in longArrayOf(800, 1_500)) {
                Thread.sleep(delayMs)
                if (VirtualLauncher.moveCallAppToPhone(call.pkg) > 0) {
                    VirtualLauncher.showDashboardForCall()
                    break
                }
            }
        }
    }

    fun decline(context: Context) {
        val call = CallState.current ?: return
        if (!send(context, call.decline) && !send(context, call.hangUp) && !openCallScreen(context, call)) telecom(ACTION_END)
    }

    fun hangUp(context: Context) {
        val call = CallState.current ?: return
        if (!send(context, call.hangUp) && !send(context, call.decline) && !openCallScreen(context, call)) telecom(ACTION_END)
    }

    /**
     * Apps whose call notifications carry no call actions but a screen to open (WeChat: full-screen ringing notification,
     * plain ongoing one): open that screen in the dashboard media pane so the driver presses the app's own button.
     * Telecom stays the fallback for notifications without any screen (it only reaches Telecom-managed calls).
     */
    private fun openCallScreen(
        context: Context,
        call: CallState.Call,
    ): Boolean {
        val screen = call.fullScreen ?: call.open
        // A ringtone-only call has no intent: its call screen is already up on the phone and gets moved over
        if (screen == null && !call.fromRingtone) return false
        val app = context.applicationContext
        Bg.run("call-screen-open") { VirtualLauncher.openCallAppInMediaPane(app, call.pkg, screen) }
        VirtualLauncher.logSink?.invoke("CALL: ${call.pkg} has no call actions - opening its call screen on the car")
        return true
    }

    fun isMuted(context: Context): Boolean =
        runCatching {
            context.getSystemService(AudioManager::class.java).isMicrophoneMute
        }.getOrDefault(false)

    fun toggleMute(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val mute = !audio.isMicrophoneMute
        runCatching { audio.isMicrophoneMute = mute }
        // Read back: the platform may ignore the request (it is meant for the telephony app)
        mutedByUs = audio.isMicrophoneMute && mute
        VirtualLauncher.logSink?.invoke("CALL: mic mute requested=$mute now=${audio.isMicrophoneMute}")
    }

    /** Call ended (no call left): release a mute set from the car. */
    fun onCallsGone(context: Context) {
        if (!mutedByUs) return
        mutedByUs = false
        runCatching { context.getSystemService(AudioManager::class.java).isMicrophoneMute = false }
    }

    private fun send(
        context: Context,
        intent: PendingIntent?,
    ): Boolean {
        intent ?: return false
        return runCatching {
            // Answer intents usually open the app's call screen. Without a display it lands on the focused one, i.e. the car's
            // app display, covering the dashboard card (WhatsApp, 2026-11-21): open it on the phone screen instead.
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            if (Build.VERSION.SDK_INT >= 34) {
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            intent.send(context, 0, null, null, null, null, options.toBundle())
            true
        }.getOrElse {
            VirtualLauncher.logSink?.invoke("CALL: call action failed ($it), trying Telecom")
            false
        }
    }

    private fun telecom(action: Int) {
        Bg.run("call-control") {
            val result = runCatching { CarInputClient.service?.callControl(action) }.getOrNull()
            VirtualLauncher.logSink?.invoke(
                "CALL: Telecom ${if (action == ACTION_ANSWER) "answer" else "end"} -> ${result ?: "no service"}",
            )
        }
    }

    const val ACTION_ANSWER = 1
    const val ACTION_END = 2
}

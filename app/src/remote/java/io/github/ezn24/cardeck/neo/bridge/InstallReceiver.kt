package io.github.ezn24.cardeck.neo.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.ezn24.cardeck.neo.util.DebugFileLog

/**
 * PackageInstaller result receiver: on STATUS_PENDING_USER_ACTION, bring the system confirmation Intent
 * to the phone's main display (the session has stopped and the virtual displays are released, so the dialog no longer lands on the car
 * side).
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        // Only accept the session submitted this time: a late broadcast from a leftover session would turn the confirmation Intent into
        // an old package
        if (ReinstallFlow.currentInstallSessionId != -1 && sessionId != ReinstallFlow.currentInstallSessionId) {
            DebugFileLog.append(
                context,
                "BRIDGE: ignoring broadcast from another session (session=$sessionId current=${ReinstallFlow.currentInstallSessionId})",
            )
            return
        }
        DebugFileLog.append(context, "BRIDGE: install status status=$status msg=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                DebugFileLog.append(context, "BRIDGE: user confirmation needed -> ${confirm.component}")
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ReinstallFlow.pendingConfirmIntent = confirm
                // Use the foreground-forwarding path only: Receiver direct launch + foreground forwarding would start the same confirmation
                // Intent twice, putting the Honor installer into a strange state (2026-10-24 photo: the "old version success page").
                // The overlay permission (background-start exemption) is already enabled by the user, so foreground forwarding is always
                // reachable.
                BridgeSetupActivity.startForConfirm(context)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                DebugFileLog.append(context, "BRIDGE: install succeeded")
                // After install, launch the settings page to ask "continue debugging", so the loop does not depend on a human
                BridgeSetupActivity.startForResume(context)
            }

            else ->
                DebugFileLog.append(
                    context,
                    "BRIDGE: install not completed status=$status msg=$message",
                )
        }
    }
}

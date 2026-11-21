package io.github.ezn24.cardeck.neo.bridge

import android.content.Context
import android.content.Intent
import io.github.ezn24.cardeck.neo.SessionController
import org.json.JSONObject

/**
 * Debug command whitelist (**never open up arbitrary shell/reflection**: if the remote channel is hijacked, the blast radius
 * is limited to these few actions). Everything goes through existing public APIs, obeying the "no shell on the UI thread" convention.
 *
 * When a remote connect/disconnect is initiated there must be an "overlay hint + automatic jump to the main screen": the user is most
 * likely
 * on the debug settings page (the address entry page) and would not see connection progress without leaving it (user feedback 2026-10-21).
 */
object BridgeCommands {
    fun handle(
        context: Context?,
        name: String,
        args: JSONObject,
    ): JSONObject {
        val ctx =
            context?.applicationContext
                ?: return fail("application context not ready")
        return when (name) {
            "ping" -> JSONObject().put("ok", true).put("detail", "pong ${System.currentTimeMillis()}")

            "get_state" ->
                JSONObject()
                    .put("ok", true)
                    .put(
                        "detail",
                        SessionController.statusLine() + " session=${if (SessionController.isSessionRunning()) "running" else "stopped"}",
                    )

            "start_connection" -> {
                // Equivalent to tapping "Start connection" on the phone: if prerequisites (Shizuku/Bluetooth) are unmet the log explains
                SessionController.ensure(ctx)
                SessionController.startConnection(ctx)
                BridgeOverlay.show(ctx, "开发者发起连接——正在进入主界面，进度见英雄卡")
                openMainUi(ctx)
                JSONObject().put("ok", true).put("detail", "connection started (jumped to the main screen)")
            }

            "stop_connection" -> {
                SessionController.disconnect(ctx)
                BridgeOverlay.show(ctx, "开发者已断开车机连接")
                JSONObject().put("ok", true).put("detail", "disconnected")
            }

            else -> fail("unknown command: $name")
        }
    }

    /** Bring the main screen to the foreground (the user may be on the debug settings page and cannot see connection progress).
     *  The background-start restriction on Activities is exempt for this app: holders of the overlay permission (SYSTEM_ALERT_WINDOW)
     *  are not bound by it, and the debug build necessarily requests that permission. */
    private fun openMainUi(ctx: Context) {
        val intent =
            Intent()
                .setClassName(ctx.packageName, "io.github.ezn24.cardeck.neo.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { ctx.startActivity(intent) }
    }

    private fun fail(detail: String): JSONObject = JSONObject().put("ok", false).put("detail", detail)
}

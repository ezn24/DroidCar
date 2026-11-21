package io.github.ezn24.cardeck.neo.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.ezn24.cardeck.neo.launcher.VirtualLauncher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car screenshot: takes the frame the compositor pushes to the head unit and saves it as PNG in "Download/CarDeck" (same folder as log
 * export).
 *
 * Trigger: the phone "Diagnostics" page button, or (to capture fleeting screens such as Gemini)
 * `adb shell am broadcast -a io.github.ezn24.cardeck.neo.CAPTURE_CAR -p io.github.ezn24.cardeck.neo`.
 */
object CarScreenshot {
    data class Result(
        val path: String,
        val width: Int,
        val height: Int,
    )

    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/CarDeck"

    /** Asynchronous capture; [onDone] is called on a background thread (null = not connected or save failed). */
    fun capture(
        context: Context,
        onDone: (Result?) -> Unit,
    ) {
        val app = context.applicationContext
        val requested =
            VirtualLauncher.requestCarFrame { bitmap ->
                Bg.run("car-screenshot") { onDone(bitmap?.let { save(app, it) }) }
            }
        if (!requested) onDone(null)
    }

    private fun save(
        context: Context,
        bitmap: android.graphics.Bitmap,
    ): Result? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "CarDeck-car-${bitmap.width}x${bitmap.height}-$stamp.png"
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                ?: throw IllegalStateException("openOutputStream returned null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Result("/storage/emulated/0/$RELATIVE_DIR/$fileName", bitmap.width, bitmap.height)
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * Capture via adb (`am broadcast`). The receiver requires the DUMP permission: held by shell, not by ordinary apps,
 * so other apps cannot use it to steal the car screen.
 */
class CarScreenshotReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: android.content.Intent,
    ) {
        val pending = goAsync()
        // Same DUMP permission lock: adb log export (release builds cannot run-as, so this is the only troubleshooting path)
        // Same DUMP permission lock: write the navigation apps' visible texts to the log (mapping new map layouts)
        if (intent.action == ACTION_DUMP_NAV_UI) {
            val log: (String) -> Unit = { line ->
                io.github.ezn24.cardeck.neo.SessionController
                    .appendLog(line)
            }
            io.github.ezn24.cardeck.neo.nav.MapsNotificationSource
                .dumpToLog(log)
            val running =
                io.github.ezn24.cardeck.neo.nav.NavAccessibilityService
                    .dump()
            if (!running) log("NAV-DUMP: accessibility service not running (enable it in system accessibility settings)")
            pending.resultData = if (running) "ok" else "accessibility service not running"
            pending.finish()
            return
        }
        if (intent.action == ACTION_EXPORT_LOG) {
            Bg.run("adb-export-log") {
                val scope = if (intent.getBooleanExtra("all", false)) LogExporter.Scope.ALL else LogExporter.Scope.CURRENT_SESSION
                pending.resultData = LogExporter.export(context, "", scope)?.displayPath ?: "failed"
                pending.finish()
            }
            return
        }
        CarScreenshot.capture(context) { result ->
            io.github.ezn24.cardeck.neo.SessionController.appendLog(
                if (result != null) {
                    "SCREENSHOT: car frame ${result.width}x${result.height} -> ${result.path}"
                } else {
                    "SCREENSHOT: capture failed (car not connected or save failed)"
                },
            )
            pending.resultData = result?.path ?: "failed"
            pending.finish()
        }
    }
}

private const val ACTION_EXPORT_LOG = "io.github.ezn24.cardeck.neo.EXPORT_LOG"
private const val ACTION_DUMP_NAV_UI = "io.github.ezn24.cardeck.neo.DUMP_NAV_UI"

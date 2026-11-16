package io.github.shaonialife.cardeck.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车机画面截图：取合成器推给车机的那一帧，存成 PNG 到「下载/CarDeck」（与日志导出同目录）。
 *
 * 触发：手机「诊断」页按钮，或（截 Gemini 这类一闪而过的画面时）
 * `adb shell am broadcast -a io.github.shaonialife.cardeck.CAPTURE_CAR -p io.github.shaonialife.cardeck`。
 */
object CarScreenshot {
    data class Result(
        val path: String,
        val width: Int,
        val height: Int,
    )

    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/CarDeck"

    /** 异步截图；[onDone] 在后台线程回调（null = 未连接或保存失败）。 */
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
                ?: throw IllegalStateException("openOutputStream 返回 null")
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
 * adb 触发截图（`am broadcast`）。接收器要求 DUMP 权限：shell 持有，普通应用拿不到，
 * 其它应用无法借它偷截车机画面。
 */
class CarScreenshotReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: android.content.Intent,
    ) {
        val pending = goAsync()
        CarScreenshot.capture(context) { result ->
            io.github.shaonialife.cardeck.SessionController.appendLog(
                if (result != null) {
                    "SCREENSHOT: 车机画面 ${result.width}x${result.height} → ${result.path}"
                } else {
                    "SCREENSHOT: 截图失败（未连接车机或保存失败）"
                },
            )
            pending.resultData = result?.path ?: "failed"
            pending.finish()
        }
    }
}

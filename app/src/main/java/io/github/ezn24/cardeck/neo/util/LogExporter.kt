package io.github.ezn24.cardeck.neo.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log export: writes the runtime log to `Download/CarDeck/CarDeck-log-<time>.txt`, visible in ordinary file managers.
 *
 * Why MediaStore.Downloads rather than the app-private directory:
 *  - `filesDir` / `getExternalFilesDir` cannot be found by ordinary users in a file manager (Android 11+
 *    system file managers hide `Android/data` outright), so the export would be useless;
 *  - MediaStore.Downloads needs no storage permission, shows up in the system "Download" folder as soon as it is written,
 *    and WeChat/QQ/email attachments can pick it directly.
 *
 * Content prefers `files/main_debug.log`: it records every appendLog (independent of the UI log switch,
 * keeping the latest 2MB at most); only when the file is unreadable does it fall back to the UI in-memory buffer (latest 20~40KB).
 */
object LogExporter {
    /** Export scope: by default only logs since this process started (earlier rounds are noise for troubleshooting). */
    enum class Scope(
        val label: String,
        val fileNameTag: String,
    ) {
        CURRENT_SESSION("仅本次启动", ""),
        ALL("全部历史日志", "-all"),
    }

    data class Result(
        val uri: Uri,
        val fileName: String,
        /** Absolute path shown to the user (what to look for in the file manager). */
        val displayPath: String,
        val bytes: Long,
        val scope: Scope,
    )

    /** Export directory (relative to the external storage root): Download/CarDeck. */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/CarDeck"

    /** Absolute path prefix shown in the file manager (primary user external storage; /sdcard under scoped storage). */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** Export and return the result; returns null on failure (the caller informs the user). Callable from any thread (does IO). */
    fun export(
        context: Context,
        fallbackText: String,
        scope: Scope = Scope.CURRENT_SESSION,
    ): Result? {
        val body = buildContent(context, fallbackText, scope)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "CarDeck-log-$stamp${scope.fileNameTag}.txt"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                // Mark pending before writing: keeps file managers/galleries from scanning a half-written file
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream returned null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Result(uri, fileName, "$DISPLAY_ROOT/$RELATIVE_DIR/$fileName", bytes.size.toLong(), scope)
        } catch (_: Exception) {
            // A half-written file (IS_PENDING=1) is invisible to the user but takes space; remove it
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun buildContent(
        context: Context,
        fallbackText: String,
        scope: Scope,
    ): String =
        buildString {
            appendLine("CarDeck runtime log")
            appendLine("Exported at: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("App version: " + versionName(context))
            appendLine("Export scope: " + scope.label)
            appendLine(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} | " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("==================================================")
            val fileLog = DebugFileLog.read(context, currentSessionOnly = scope == Scope.CURRENT_SESSION)
            if (!fileLog.isNullOrBlank()) {
                if (scope == Scope.CURRENT_SESSION) {
                    appendLine("(Only logs since this process started; tap \"Export all\" for history)")
                } else {
                    appendLine("(Full file log; the file keeps at most 2MB, the system retains only the latest part when exceeded)")
                }
                append(fileLog)
                if (!fileLog.endsWith('\n')) appendLine()
            } else {
                // Right after the start marker is written, before other logs hit disk (or when the file is unreadable), the UI buffer is
                // still a valid source
                appendLine("(File log unavailable -- exporting the UI buffer, may be incomplete)")
                append(fallbackText)
            }
        }

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("?")
}

package io.github.ezn24.cardeck.neo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/**
 * Car wallpaper storage. Two slots, dark/light (2026-11-17), switched automatically with the phone's dark/light mode:
 * each slot's source = built-in default (assets/wallpaper_default.jpg) / built-in pattern ([WallpaperPresets],
 * drawn in code) / user image (dark uses files/wallpaper.jpg keeping the old file name, light uses files/wallpaper_light.jpg);
 * the light slot defaults to "same as dark".
 *
 * The compositor uploads the bitmap as a GL texture; after the wallpaper changes, a [version] increment notifies a reload.
 */
object WallpaperStore {
    /** Built-in default wallpaper (file name inside assets). */
    const val DEFAULT_ASSET = "wallpaper_default.jpg"

    private const val USER_FILE = "wallpaper.jpg"
    private const val USER_FILE_LIGHT = "wallpaper_light.jpg"
    private const val PREFS = "wallpaper"

    enum class Slot { DARK, LIGHT }

    const val SRC_DEFAULT = "default"
    const val SRC_USER = "user"
    const val SRC_SAME = "same"
    const val SRC_PRESET_PREFIX = "preset:"

    private fun userFile(
        context: Context,
        slot: Slot,
    ) = File(context.filesDir, if (slot == Slot.DARK) USER_FILE else USER_FILE_LIGHT)

    fun hasUserImage(
        context: Context,
        slot: Slot,
    ): Boolean = userFile(context, slot).let { it.exists() && it.length() > 0 }

    /** Slot source; when unset dark = the old user image (if any) otherwise the default, light = same as dark. */
    fun source(
        context: Context,
        slot: Slot,
    ): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(slot.name, null)
            ?: when (slot) {
                Slot.DARK -> if (hasUserImage(context, Slot.DARK)) SRC_USER else SRC_DEFAULT
                Slot.LIGHT -> SRC_SAME
            }

    fun setSource(
        context: Context,
        slot: Slot,
        source: String,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(slot.name, source)
            .apply()
        markChanged()
    }

    /** Whether the phone is currently dark (app-level configuration, unaffected by Activity-local night mode). */
    fun isNight(context: Context): Boolean =
        (
            context.applicationContext.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        ) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Currently effective slot and source (the light slot is used only in light mode and when the light slot is not "same as dark"). */
    private fun effective(context: Context): Pair<Slot, String> {
        if (!isNight(context)) {
            val light = source(context, Slot.LIGHT)
            if (light != SRC_SAME) return Slot.LIGHT to light
        }
        return Slot.DARK to source(context, Slot.DARK)
    }

    @Volatile private var lastNight: Boolean? = null

    /** Attached once at process start: when phone dark/light switches and the two slots differ -> notify the car side to reload the wallpaper. */
    fun attach(app: android.app.Application) {
        lastNight = isNight(app)
        app.registerComponentCallbacks(
            object : android.content.ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                    val night = isNight(app)
                    if (night == lastNight) return
                    lastNight = night
                    io.github.ezn24.cardeck.neo.launcher.AssistiveDot
                        .refresh()
                    if (source(app, Slot.LIGHT) != SRC_SAME) markChanged()
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            },
        )
    }

    /** Decode by source ([maxSize] limits the edge length); falls back to the built-in default on failure. */
    fun decodeSource(
        context: Context,
        slot: Slot,
        source: String,
        maxSize: Int,
    ): Bitmap? =
        when {
            source == SRC_USER -> decodeFile(userFile(context, slot), maxSize)
            source.startsWith(SRC_PRESET_PREFIX) ->
                WallpaperPresets.render(source.removePrefix(SRC_PRESET_PREFIX), maxSize)
            else -> null
        } ?: decodeDefault(context, maxSize)

    private val versionCounter =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** Current wallpaper version (incremented on change; receivers use it to decide whether to reload). */
    val version: Long get() = versionCounter.get()

    // ---- Change notifications (listener pattern; a receiver should read the current state once right after registering as a fallback)
    // ----

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Long) -> Unit>()

    /** Register a wallpaper change listener (called on the main thread; the argument is the new version number). */
    fun addListener(listener: (Long) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    fun markChanged() {
        val version = versionCounter.incrementAndGet()
        listeners.forEach { runCatching { it(version) } }
    }

    /**
     * Decode the current wallpaper (call on a background thread; falls back to the built-in default, then to null so the caller paints a
     * solid color).
     * The edge length is limited by [maxSize] (the caller scales proportionally for aspectFill drawing).
     */
    fun decode(
        context: Context,
        maxSize: Int = 2560,
    ): Bitmap? {
        val (slot, source) = effective(context)
        return decodeSource(context, slot, source, maxSize)
    }

    private fun decodeFile(
        file: File,
        maxSize: Int,
    ): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (info.size.width > maxSize || info.size.height > maxSize) {
                    decoder.setTargetSampleSize(
                        maxOf(info.size.width, info.size.height) / maxSize + 1,
                    )
                }
            }
        }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun decodeDefault(
        context: Context,
        maxSize: Int,
    ): Bitmap? =
        runCatching {
            context.assets.open(DEFAULT_ASSET).use { input ->
                val options =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeStream(input, null, this)
                    }
                context.assets.open(DEFAULT_ASSET).use { stream ->
                    options.inJustDecodeBounds = false
                    options.inSampleSize = sampleSize(options.outWidth, options.outHeight, maxSize)
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }
        }.getOrNull()

    /** Copy the user-selected image into this slot and make it the source (re-picking the same image also overwrites; idempotent). */
    fun setFromUri(
        context: Context,
        uri: Uri,
        slot: Slot = Slot.DARK,
    ): Boolean =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                userFile(context, slot).outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            setSource(context, slot, SRC_USER)
            true
        }.getOrDefault(false)

    private fun sampleSize(
        width: Int,
        height: Int,
        maxSize: Int,
    ): Int {
        var sample = 1
        while (width / sample > maxSize || height / sample > maxSize) sample *= 2
        return sample
    }

    // ---- Desktop slice: aspectFill bitmap of the whole stream canvas (shared by GridActivity/DockActivity) ----

    /** Two-size cache (desktop = oversampled display size, dock = stream size, one each). */
    private val canvasCache = HashMap<String, Bitmap>()
    private var canvasCacheVersion = -1L

    /** Canvas bitmap ready listeners (called after the first build / rebuild on a version change; the argument = canvas version number). */
    private val canvasReadyListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()

    fun addCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.add(listener)
    }

    fun removeCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.remove(listener)
    }

    /**
     * Wallpaper bitmap aspectFill-ed over the "whole video stream canvas" (the caller offsets the slice to its own window position).
     * Cached by size + version (one each for desktop and dock); call on a background thread; the returned bitmap is read-only.
     */
    @Synchronized
    fun aspectFillCanvas(
        context: Context,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Bitmap? {
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val key = "${canvasWidth}x$canvasHeight"
        canvasCache[key]?.let { cached ->
            if (canvasCacheVersion == version) return cached
        }
        if (canvasCacheVersion != version) canvasCache.clear()
        val source = decode(context, maxOf(canvasWidth, canvasHeight) * 2) ?: return null
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val scale =
            maxOf(
                canvasWidth.toFloat() / source.width,
                canvasHeight.toFloat() / source.height,
            )
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        // Must scale-draw with the "destination rect" overload: drawBitmap(bmp, x, y, paint) draws at the source pixel
        // size, so when the source is smaller than the canvas (e.g. a portrait photo narrower than the landscape canvas) the right side
        // is left empty (transparent black)
        val dest =
            android.graphics.Rect(
                ((canvasWidth - drawWidth) / 2f).toInt(),
                ((canvasHeight - drawHeight) / 2f).toInt(),
                ((canvasWidth - drawWidth) / 2f).toInt() + drawWidth.toInt(),
                ((canvasHeight - drawHeight) / 2f).toInt() + drawHeight.toInt(),
            )
        canvas.drawBitmap(source, null, dest, paint)
        canvasCacheVersion = version
        canvasCache[key] = bitmap
        canvasReadyListeners.forEach { runCatching { it(version) } }
        return bitmap
    }
}

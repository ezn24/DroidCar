package io.github.ezn24.cardeck.neo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/**
 * 车机壁纸存储。深色/浅色两个槽位（2026-11-17），跟随手机深浅色自动切换：
 * 每个槽位的来源 = 内置默认（assets/wallpaper_default.jpg）/ 内置图案（[WallpaperPresets]，
 * 代码绘制）/ 用户图片（深色 files/wallpaper.jpg 沿用旧文件名，浅色 files/wallpaper_light.jpg）；
 * 浅色槽位默认「同深色」。
 *
 * 合成器以位图上传 GL 纹理；更换壁纸后经 [version] 递增通知重载。
 */
object WallpaperStore {
    /** 内置默认壁纸（assets 内文件名）。 */
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

    /** 槽位来源；未设置时深色 = 旧版用户图（若有）否则默认，浅色 = 同深色。 */
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

    /** 手机当前是否深色（应用级配置，不受 Activity 局部夜间模式影响）。 */
    fun isNight(context: Context): Boolean =
        (
            context.applicationContext.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        ) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** 当前生效的槽位与来源（浅色模式且浅色槽位不是「同深色」才用浅色）。 */
    private fun effective(context: Context): Pair<Slot, String> {
        if (!isNight(context)) {
            val light = source(context, Slot.LIGHT)
            if (light != SRC_SAME) return Slot.LIGHT to light
        }
        return Slot.DARK to source(context, Slot.DARK)
    }

    @Volatile private var lastNight: Boolean? = null

    /** 进程启动时挂一次：手机深浅色切换且两槽位不同 → 通知车机侧重载壁纸。 */
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

    /** 按来源解码（[maxSize] 限边长）；失败回退内置默认。 */
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

    /** 当前壁纸版本（更换后递增；接收方据此判断是否需要重载）。 */
    val version: Long get() = versionCounter.get()

    // ---- 变更通知（监听器模式；接收方注册后应立即读一次当前状态兜底） ----

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Long) -> Unit>()

    /** 注册壁纸变更监听（在主线程回调；参数为新版本号）。 */
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
     * 解码当前壁纸（后台线程调用；失败回退内置默认，再失败回退 null 由调用方画纯色）。
     * 按 [maxSize] 限制边长（aspectFill 由调用方按比例缩放绘制）。
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

    /** 复制用户选择的图片到该槽位并设为来源（重复选择同一图也覆盖，幂等）。 */
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

    // ---- 桌面切片：全流画布的 aspectFill 位图（GridActivity/DockActivity 共用） ----

    /** 双尺寸缓存（桌面 = 超采样屏尺寸、dock = 流尺寸各一份）。 */
    private val canvasCache = HashMap<String, Bitmap>()
    private var canvasCacheVersion = -1L

    /** 画布位图就绪监听（首次构建/版本更换重建后回调；参数 = 画布版本号）。 */
    private val canvasReadyListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()

    fun addCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.add(listener)
    }

    fun removeCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.remove(listener)
    }

    /**
     * 按「整个视频流画布」做 aspectFill 的壁纸位图（调用方把切片偏移到自己的窗口位置）。
     * 按尺寸+版本缓存（桌面与 dock 各一份）；后台线程调用，返回的位图只读。
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
        // 必须用「目标矩形」重载缩放绘制——drawBitmap(bmp, x, y, paint) 按原图像素
        // 尺寸绘制：源图小于画布时（如竖屏照片 < 横屏画布宽）右侧留空（透明黑）
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

package io.github.shaonialife.cardeck.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仪表盘卡片边距（流像素）——地图 tile 与音乐卡共用的契约值，四方统一引用：
 * 本类 SPLIT 绘制矩形、VirtualLauncher 建屏 tile 尺寸、GridActivity 音乐卡布局、
 * TouchRouter tile 触摸命中域。此前 16 在四个文件各自定义/裸写、仅靠注释结盟，
 * 改一处即静默错位（触摸命中域/卡片布局/绘制矩形三者不一致，坏味道审查 P1 收敛）。
 */
val CARD_MARGIN_PX: Int get() = (16f * DashMetrics.scale).toInt().coerceAtLeast(4)

/**
 * 仪表盘版式缩放（2026-11-17：此前所有流像素常量按 1920×720 写死，换分辨率后
 * 边距/中缝/圆角比例全变）。以**流画布短边 720px 为 1.0** 等比缩放——1920×720 车机
 * 下所有值与旧版完全一致。会话 attach 时由 VirtualLauncher 按画布设定；单测不设即 1.0。
 */
object DashMetrics {
    const val BASE_SHORT_SIDE = 720f

    @Volatile
    var scale: Float = 1f
        private set

    fun configure(
        streamWidth: Int,
        streamHeight: Int,
    ) {
        scale = (minOf(streamWidth, streamHeight) / BASE_SHORT_SIDE).coerceIn(0.4f, 3f)
    }
}

/**
 * 仪表盘分屏中缝宽（流像素）——地图 tile 与音乐卡之间**视觉**缝隙，几何契约同
 * [CARD_MARGIN_PX]：tile 内容侧边缘 = 把手中心 − DASH_GAP_PX/2，音乐卡内容侧
 * 边缘 = 把手中心 + DASH_GAP_PX/2（VirtualLauncher.dashSplitGeom 统一推导）。
 */
val DASH_GAP_PX: Float get() = 24f * DashMetrics.scale

/**
 * 分屏把手**触达**宽（流像素）：把手 View 宽度与 TouchRouter 粘性路由域按此值，
 * 比 [DASH_GAP_PX] 宽——两侧各溢出 (TOUCH−GAP)/2 压在卡片上扩大触控目标
 * （音乐卡侧把手叠在其上层；地图侧溢出段被 tile 盖住不可见但可触摸——
 * 视觉与触摸分层，2026-10-22 用户反馈「中缝太宽」的解法）。
 */
val DASH_HANDLE_TOUCH_PX: Float get() = 60f * DashMetrics.scale

/**
 * 双虚拟屏 GL 合成器：dock 屏 + 应用屏（原生 / 超采样两块，按活动切换）→ 拼接输出到编码器输入 Surface。
 *
 * 三路 SurfaceTexture 作为各虚拟屏的输出；GL 线程轮询新帧标志，有新帧即重绘合成画面。
 * 应用区同一时刻只画两块应用屏中的**活动**一块（[requestActiveApp] 切换；超采样屏
 * 等其画出首帧再切换，避免闪黑）。两块应用屏的内容持续消费（纹理保持最新）。
 * 输入 Surface 在 EGL/纹理就绪后经 [onSurfacesReady] 交给调用方去 createVirtualDisplay。
 */
class DualDisplayComposer(
    private val output: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    /** true：dock 是底部横条（竖屏车机）；false：dock 是左侧竖条（横屏车机）。 */
    private val dockAtBottom: Boolean = false,
    /** 地图在上、媒体在下（竖屏车机 / 音乐条）；默认随 dock 位置（旧行为）。 */
    private val splitVertical: Boolean = dockAtBottom,
) {
    /** 仪表盘↔桌面滑动进度（0=tile 原位，1=完全滑出左侧），由 GridActivity 随滑动驱动。 */
    @Volatile
    var tileSlideFraction: Float = 0f

    /**
     * 地图 tile 把手侧边缘覆盖值（流像素，顶部原点；横屏=tile 右缘 x、竖屏=tile
     * 下缘 y）。>0 时替代 SPLIT 公式值——拖动分屏把手时由 VirtualLauncher 逐帧写入，
     * **只改绘制矩形（纹理整幅拉伸），不动 buffer、不动虚拟屏**；松手 commit 才真实
     * resize。≤0 = 用默认中线公式。语义与 [tileSlideFraction] 同类：连续视觉态。
     */
    @Volatile
    private var tileEdgeOverride = -1f

    fun setTileEdgeOverride(edgePx: Float) {
        tileEdgeOverride = edgePx
    }

    /**
     * 目标帧率（30/60，合成出帧节奏上限；「显示」设置运行中实时切换）。
     * 有新输入帧时按 1000/targetFps 节流；静止画面保底 30fps 心跳（编码器不能断粮）。
     */
    @Volatile
    var targetFps: Int = 30

    companion object {
        private const val TAG = "DualComposer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val TILE_CORNER_RADIUS: Float get() = 24f * DashMetrics.scale

        /** 2D 纹理程序用的单位纹理矩阵（Bitmap 已按目标方向摆好顶点）。 */
        private val IDENTITY_ST =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )
    }

    private val running = AtomicBoolean(false)
    private var glThread: Thread? = null

    /** start() 的就绪闩：GL 交付输入 Surface 后放行（注意不能等合成循环退出） */
    @Volatile
    private var startLatch: CountDownLatch? = null

    /** 是否创建了超采样应用屏（start 时根据传入尺寸确定）。 */
    @Volatile
    private var hasHiInput = false

    /** 是否创建了地图屏输入（start 时根据传入尺寸确定）。 */
    @Volatile
    private var hasMapInput = false

    /** 当前应用区显示的是超采样屏（true）还是原生屏（false）。触摸路由以它为准。 */
    @Volatile
    private var activeAppHi = false

    /** 待切换的目标屏（true=超采样屏；null=无待切换）；切屏等目标出帧，避免闪黑/闪旧帧。 */
    @Volatile
    private var pendingTargetHi: Boolean? = null

    @Volatile
    private var pendingBaseFrame = 0

    @Volatile
    private var pendingSince = 0L

    /**
     * 叠加卡片（输出画面坐标，顶部原点）。由调用方在后台线程生成位图后设置；
     * GL 线程负责上传纹理并绘制在最上层。触控命中用同一坐标，见 [OverlayCard.contains]。
     */
    @Volatile
    var overlayCard: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
            wake()
        }

    /** 合成循环的唤醒信号：有输入新帧 / 叠加层变化 / 布局动画开始时立即叫醒。 */
    private val frameSignal = Object()

    private fun wake() {
        synchronized(frameSignal) { frameSignal.notifyAll() }
    }

    @Volatile
    private var overlayDirty = false

    /** 待处理的截图请求（GL 线程下一次合成后读回并回调；回调在 GL 线程，调用方自行切线程）。 */
    @Volatile
    private var captureRequest: ((android.graphics.Bitmap?) -> Unit)? = null

    /**
     * 截取合成后的整幅车机画面（与推流同一帧：dock + 桌面/应用 + 地图/媒体卡 + 叠加层）。
     * 各虚拟屏单独 screencap 只能拿到碎片，完整画面只在这里存在。
     */
    fun requestCapture(callback: (android.graphics.Bitmap?) -> Unit) {
        captureRequest = callback
        wake()
    }

    /** glReadPixels 读回输出画面（GL 原点在左下，需上下翻转）。 */
    private fun readOutputFrame(): android.graphics.Bitmap? =
        runCatching {
            val buffer =
                java.nio.ByteBuffer
                    .allocateDirect(outWidth * outHeight * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, outWidth, outHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()
            val raw = android.graphics.Bitmap.createBitmap(outWidth, outHeight, android.graphics.Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
            android.graphics.Bitmap
                .createBitmap(raw, 0, 0, outWidth, outHeight, flip, false)
                .also { raw.recycle() }
        }.getOrNull()

    /**
     * 桌面页点（输出画面坐标）。页点本在应用屏的 GridActivity 里，但仪表盘的地图/媒体
     * 卡在 GL 层画在应用屏之上，会把页点盖掉；所以由 GridActivity 把页点快照成位图交给
     * 这里，在卡片之后、dock 之前补画。是否绘制由 [VirtualLauncher.pageDotsOnTop] 决定，
     * 与 GridActivity 隐藏自身页点用同一判据（避免两份重叠或都不显示）。
     */

    /** 页点显隐淡入淡出（与 GridActivity 本地页点同节奏：入 120ms / 出 220ms）。 */
    @Volatile
    private var pageDotsTarget = 0f

    @Volatile
    private var pageDotsFrom = 0f

    @Volatile
    private var pageDotsChangedAt = 0L

    fun setPageDotsVisible(visible: Boolean) {
        val target = if (visible) 1f else 0f
        if (target == pageDotsTarget) return
        val now = android.os.SystemClock.uptimeMillis()
        pageDotsFrom = pageDotsAlphaAt(now)
        pageDotsTarget = target
        pageDotsChangedAt = now
        wake()
    }

    private fun pageDotsDuration(): Long = if (pageDotsTarget > 0f) 120L else 220L

    private fun pageDotsAlphaAt(now: Long): Float {
        val t = ((now - pageDotsChangedAt).toFloat() / pageDotsDuration()).coerceIn(0f, 1f)
        return pageDotsFrom + (pageDotsTarget - pageDotsFrom) * t
    }

    private fun pageDotsFading(now: Long): Boolean = now - pageDotsChangedAt < pageDotsDuration()

    @Volatile
    var pageDots: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
            wake()
        }

    /** 辅助小白点（[io.github.shaonialife.cardeck.launcher.AssistiveDot]）：独立纹理槽，画在最上层。 */
    @Volatile
    var assistiveDot: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
            wake()
        }

    /** 一张叠加位图的 GL 纹理与已上传的卡片引用（仅 GL 线程访问）。 */
    private class OverlaySlot {
        var texture = 0
        var uploaded: OverlayCard? = null
    }

    private val overlaySlot = OverlaySlot()
    private val pageDotsSlot = OverlaySlot()
    private val assistiveDotSlot = OverlaySlot()

    /** 超采样输入（GL 线程创建；start() 返回后对其他线程安全可读）。 */
    @Volatile
    private var appHi: Input? = null

    /** 原生应用屏输入（同上；requestActiveApp 读其帧号作切换判据）。 */
    @Volatile
    private var appInput: Input? = null

    /** 地图屏输入（GL 线程创建）。 */
    @Volatile
    private var mapInput: Input? = null

    @Volatile
    var onSurfacesReady: ((dock: Surface, app: Surface, appHi: Surface?, map: Surface?, media: Surface) -> Unit)? = null

    @Volatile
    var showMediaPane = false

    @Volatile
    private var mediaInput: Input? = null

    fun hasMediaContent(): Boolean = mediaInput?.hasContent == true

    /**
     * 语音助理层：Gemini 浮层所在的透明虚拟屏（CarPlayAssist，与应用区同尺寸）。
     * 可见时按预乘 alpha 叠在地图/媒体卡之上、dock 之下——浮层本身半透明，
     * 卡片照常可见，光效与对话条铺满整个应用区。
     */
    @Volatile
    var assistLayerVisible = false

    @Volatile
    private var assistLayerInput: Input? = null

    /** 交给助理虚拟屏的 Surface（合成器未就绪时 null）。 */
    fun assistLayerSurface(): Surface? = assistLayerInput?.surface

    /** 语音助理浮层开在应用屏上：暂停绘制地图/媒体卡（它们会盖住原生浮层）。 */
    @Volatile
    var tilesSuppressed = false

    @Throws(IllegalStateException::class)
    fun start(
        dockWidthPx: Int,
        dockHeightPx: Int,
        appWidthPx: Int,
        appHeightPx: Int,
        appHiWidthPx: Int = 0,
        appHiHeightPx: Int = 0,
        mapWidthPx: Int = 0,
        mapHeightPx: Int = 0,
        mediaWidthPx: Int = 1,
        mediaHeightPx: Int = 1,
    ) {
        if (running.getAndSet(true)) return
        hasHiInput = appHiWidthPx > 0 && appHiHeightPx > 0
        hasMapInput = mapWidthPx > 0 && mapHeightPx > 0
        val ready = CountDownLatch(1)
        startLatch = ready
        var failure: IllegalStateException? = null
        glThread =
            Thread({
                try {
                    runGl(
                        dockWidthPx,
                        dockHeightPx,
                        appWidthPx,
                        appHeightPx,
                        appHiWidthPx,
                        appHiHeightPx,
                        mapWidthPx,
                        mapHeightPx,
                        mediaWidthPx,
                        mediaHeightPx,
                    )
                } catch (error: IllegalStateException) {
                    failure = error
                } finally {
                    ready.countDown()
                }
            }, "dual-composer").apply {
                isDaemon = true
                start()
            }
        ready.await()
        failure?.let {
            running.set(false)
            throw it
        }
    }

    /**
     * 切换应用区显示：false=原生屏，true=超采样屏。目标屏出帧后生效（期间继续显示原
     * 画面）：等待「目标屏帧号前进」（新应用首帧）；600ms 兜底（目标屏有历史内容时
     * 平滑切换）；2s 超时兜底（目标屏迟迟不出帧也要把画面切过去）。
     * 无超采样屏（未创建）时恒为原生屏。
     */
    fun requestActiveApp(hi: Boolean) {
        val target = if (!hasHiInput) false else hi
        if (activeAppHi == target && pendingTargetHi == null) return
        val targetInput = if (target) appHi else appInput
        pendingBaseFrame = targetInput?.frameCount ?: 0
        pendingSince = android.os.SystemClock.uptimeMillis()
        pendingTargetHi = target
        if (!hasHiInput) {
            // 无超采样屏：无需等待，直接生效
            activeAppHi = false
            pendingTargetHi = null
        }
    }

    /** 应用区当前活动屏（触摸路由/坐标换算以此为准）。 */
    fun isActiveAppHi(): Boolean = activeAppHi

    /**
     * 地图屏（tile）布局状态：HIDDEN=普通桌面；SPLIT=采样画面中央带区映射到
     * 应用区左半（地图中心≈车辆位置，裁剪语义正确）；FULLSCREEN=整个应用区。
     * 地图屏始终以全屏尺寸创建（应用窗口稳定，任务不迁移），分/全屏只是采样几何。
     */
    enum class TileLayout { HIDDEN, SPLIT, FULLSCREEN }

    @Volatile
    private var tileLayout = TileLayout.HIDDEN

    @Volatile
    private var lastPortraitTileDiag = 0L

    private fun launcherDashHint(): Boolean =
        try {
            VirtualLauncher.dashboardMode
        } catch (_: Throwable) {
            false
        }

    /** 切换地图 tile 布局。 */
    fun setTileLayout(layout: TileLayout) {
        if (tileLayout == layout) return
        val now = android.os.SystemClock.uptimeMillis()
        val previous = layoutMotion
        layoutMotion =
            LayoutMotion(
                previous?.valueAt(now) ?: if (tileLayout == TileLayout.FULLSCREEN) 1f else 0f,
                if (layout == TileLayout.FULLSCREEN) 1f else 0f,
                now,
            )
        tileLayout = layout
        wake()
    }

    @Volatile
    private var layoutMotion: LayoutMotion? = null

    @Volatile
    private var appEntryAt = 0L

    @Volatile
    private var appEntryFromMedia = false

    fun animateAppEntry(fromMediaCard: Boolean = false) {
        appEntryFromMedia = fromMediaCard
        appEntryAt = android.os.SystemClock.uptimeMillis()
        wake()
    }

    fun isLayoutTransitioning(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        return layoutMotion?.isRunning(now) == true || now - appEntryAt < LayoutMotion.DURATION_MS
    }

    /**
     * 地图屏动态 resize 后同步消费端 buffer 尺寸。VirtualDisplay.resize 只改系统侧
     * 显示设备——SurfaceTexture 的 defaultBufferSize 仍锁在创建时的旧尺寸，投影会把
     * 新尺寸内容画进旧 buffer 的左上区域（tile 内容偏居左、宽度只剩比例值、右侧全黑）。
     */
    fun resizeMapInput(
        width: Int,
        height: Int,
    ) {
        mapInput?.surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        glThread?.interrupt()
        glThread = null
    }

    private class Input(
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
    ) {
        @Volatile
        var hasNewFrame = false

        /** 是否已产出过至少一帧（未产出的输入不参与绘制，避免空纹理） */
        @Volatile
        var hasContent = false

        /** 累计产出帧数（活动屏切换的「已出帧」判据）。 */
        @Volatile
        var frameCount = 0
    }

    private fun runGl(
        dockW: Int,
        dockH: Int,
        appW: Int,
        appH: Int,
        hiW: Int,
        hiH: Int,
        mapW: Int,
        mapH: Int,
        mediaW: Int,
        mediaH: Int,
    ) {
        // ---- EGL ----
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        checkEgl(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")
        checkEgl(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1), "eglInitialize")

        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        checkEgl(
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0,
            "eglChooseConfig",
        )
        val context: EGLContext =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        checkEgl(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext")
        val outputSurface: EGLSurface =
            EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                output,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
        checkEgl(outputSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
        checkEgl(
            EGL14.eglMakeCurrent(display, outputSurface, outputSurface, context),
            "eglMakeCurrent",
        )

        // ---- 输入纹理 ----
        fun newInput(
            width: Int,
            height: Int,
        ): Input {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val st = SurfaceTexture(tex[0])
            st.setDefaultBufferSize(width, height)
            val input = Input(tex[0], st, Surface(st))
            st.setOnFrameAvailableListener {
                input.hasNewFrame = true
                wake()
            }
            return input
        }

        val dock = newInput(dockW, dockH)
        val app = newInput(appW, appH).also { appInput = it }
        val hi = if (hasHiInput) newInput(hiW, hiH).also { appHi = it } else null
        val map = if (hasMapInput) newInput(mapW, mapH).also { mapInput = it } else null
        val media = newInput(mediaW, mediaH).also { mediaInput = it }
        val assistLayer = newInput(appW, appH).also { assistLayerInput = it }
        // ---- 着色器 ----
        val program = buildProgram(oes = true)
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val uClipEnabled = GLES20.glGetUniformLocation(program, "uClipEnabled")
        val uClipRect = GLES20.glGetUniformLocation(program, "uClipRect")
        val uClipRadius = GLES20.glGetUniformLocation(program, "uClipRadius")
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val matrix = FloatArray(16)

        // ---- 叠加卡片着色器（普通 2D 纹理；Bitmap 由 Canvas 预排版）----
        val overlayProgram = buildProgram(oes = false)
        val oaPos = GLES20.glGetAttribLocation(overlayProgram, "aPos")
        val oaTexCoord = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        val ouSTMatrix = GLES20.glGetUniformLocation(overlayProgram, "uSTMatrix")
        val ouTexture = GLES20.glGetUniformLocation(overlayProgram, "uTexture")
        val ouAlpha = GLES20.glGetUniformLocation(overlayProgram, "uAlpha")

        /** 上传/更新叠加卡片纹理（仅 GL 线程；卡片引用变化时才重新上传）。 */
        fun syncOverlayTexture(
            slot: OverlaySlot,
            source: OverlayCard?,
        ): OverlayCard? {
            val card =
                source ?: run {
                    slot.uploaded = null
                    return null
                }
            if (slot.texture != 0 && card.bitmap === slot.uploaded?.bitmap) {
                slot.uploaded = card
                return card
            }
            if (slot.texture == 0) {
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                slot.texture = tex[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, card.bitmap, 0)
            slot.uploaded = card
            return card
        }

        /**
         * 绘制叠加卡片。[left]/[top]/[right]/[bottom] 是输出画面坐标（顶部原点），
         * 这里转成 GL 底部原点；Bitmap 首行在顶部，故纹理 v 轴与顶点方向相反。
         */
        fun drawOverlay(
            slot: OverlaySlot,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            alpha: Float = 1f,
        ) {
            GLES20.glUseProgram(overlayProgram)
            GLES20.glUniform1i(ouTexture, 0)
            GLES20.glUniform1f(ouAlpha, alpha)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.texture)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUniformMatrix4fv(ouSTMatrix, 1, false, IDENTITY_ST, 0)

            fun px(v: Float) = v / outWidth * 2f - 1f

            fun py(v: Float) = v / outHeight * 2f - 1f
            val yTop = outHeight - top
            val yBottom = outHeight - bottom
            val positions =
                vertexBuffer(
                    posBuf,
                    px(left),
                    py(yBottom),
                    px(right),
                    py(yBottom),
                    px(left),
                    py(yTop),
                    px(right),
                    py(yTop),
                )
            // Bitmap v=0 在顶部：底部顶点取 v=1，顶部顶点取 v=0
            val coords = vertexBuffer(texBuf, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            GLES20.glVertexAttribPointer(oaPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(oaPos)
            GLES20.glVertexAttribPointer(oaTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(oaTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisable(GLES20.GL_BLEND)
            // 恢复主着色器状态（后续帧继续用 OES 程序）
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTexture, 0)
        }

        /** [u0]/[u1]：横向纹理采样区间（地图分屏时采样缓冲中央带区用）。 */
        fun drawInputUV(
            input: Input,
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
            u0: Float = 0f,
            u1: Float = 1f,
            cornerRadius: Float = 0f,
        ) {
            input.surfaceTexture.getTransformMatrix(matrix)
            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, matrix, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
            // 圆角裁剪：shader 用 gl_FragCoord（GL 底部原点）与 uClipRect 比较，
            // 故直接传顶点坐标（本函数 y 即 GL 坐标）。此前翻转成屏幕坐标——
            // 全高 tile（横屏）对称区间恰好重合未暴露；竖屏半高 tile 顶点区域
            // 与裁剪区域完全不相交，整块被 discard（「地图卡不可见」根因）
            if (cornerRadius > 0f) {
                GLES20.glUniform1i(uClipEnabled, 1)
                GLES20.glUniform4f(uClipRect, x0, y0, x1, y1)
                GLES20.glUniform1f(uClipRadius, cornerRadius)
            } else {
                GLES20.glUniform1i(uClipEnabled, 0)
            }

            // 像素坐标 → 裁剪坐标（GL 左下原点；纹理翻转由 uSTMatrix 处理，勿重复翻转）
            fun px(v: Float) = v / outWidth * 2f - 1f

            fun py(v: Float) = v / outHeight * 2f - 1f
            val positions =
                vertexBuffer(
                    posBuf,
                    px(x0),
                    py(y0),
                    px(x1),
                    py(y0),
                    px(x0),
                    py(y1),
                    px(x1),
                    py(y1),
                )
            val coords = vertexBuffer(texBuf, u0, 0f, u1, 0f, u0, 1f, u1, 1f)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun drawInput(
            input: Input,
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
        ) = drawInputUV(input, x0, x1, y0, y1, u0 = 0f, u1 = 1f)

        fun composeFrame(fullRedraw: Boolean) {
            // 待切应用屏：目标屏出帧即切（帧号前进 / 已有内容 600ms 兜底 / 2s 超时兜底）
            pendingTargetHi?.let { targetHi ->
                val nowMs = android.os.SystemClock.uptimeMillis()
                val target = if (targetHi) hi else app
                val advanced = target != null && target.frameCount > pendingBaseFrame
                if (advanced ||
                    (target?.hasContent == true && nowMs - pendingSince >= 600L) ||
                    nowMs - pendingSince >= 2_000L
                ) {
                    activeAppHi = targetHi
                    pendingTargetHi = null
                }
            }
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // 只对有新帧的输入 updateTexImage；已有内容的输入沿用旧纹理（画面持久）。
            // 两块应用屏都持续消费（非活动屏也保持最新，切换时不闪旧帧）。
            if (dock.hasNewFrame) {
                dock.hasNewFrame = false
                dock.hasContent = true
                dock.frameCount++
                dock.surfaceTexture.updateTexImage()
            }
            if (app.hasNewFrame) {
                app.hasNewFrame = false
                app.hasContent = true
                app.frameCount++
                app.surfaceTexture.updateTexImage()
            }
            hi?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            map?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            if (media.hasNewFrame) {
                media.hasNewFrame = false
                media.hasContent = true
                media.frameCount++
                media.surfaceTexture.updateTexImage()
            }
            if (assistLayer.hasNewFrame) {
                assistLayer.hasNewFrame = false
                assistLayer.hasContent = true
                assistLayer.frameCount++
                assistLayer.surfaceTexture.updateTexImage()
            }
            val activeInput = if (activeAppHi) hi else app
            val nowMs = android.os.SystemClock.uptimeMillis()
            val appLeft = if (dockAtBottom) 0f else dockW.toFloat()
            val appBottom = if (dockAtBottom) dockH.toFloat() else 0f
            if (activeInput?.hasContent == true) {
                val entry = LayoutMotion.progress(nowMs - appEntryAt)
                val inset = (1f - entry) * (outWidth - appLeft) * 0.035f
                val mediaStart = VirtualLauncher.dashSplitPos + DASH_GAP_PX / 2f
                val originLeft = if (splitVertical) appLeft + CARD_MARGIN_PX else mediaStart
                val originTop = if (splitVertical) mediaStart else CARD_MARGIN_PX.toFloat()

                fun expand(
                    from: Float,
                    to: Float,
                ) = from + (to - from) * entry
                if (appEntryFromMedia && entry < 1f) {
                    drawInputUV(
                        activeInput,
                        expand(originLeft, appLeft),
                        expand(outWidth - CARD_MARGIN_PX.toFloat(), outWidth.toFloat()),
                        expand(appBottom + CARD_MARGIN_PX, appBottom),
                        expand(outHeight - originTop, outHeight.toFloat()),
                        cornerRadius = (1f - entry) * TILE_CORNER_RADIUS,
                    )
                } else {
                    drawInputUV(
                        activeInput,
                        appLeft + inset,
                        outWidth - inset,
                        appBottom + inset,
                        outHeight - inset,
                        cornerRadius = (1f - entry) * TILE_CORNER_RADIUS,
                    )
                }
            }
            if (map?.hasContent == true && tileLayout != TileLayout.HIDDEN && !tilesSuppressed) {
                val expanded =
                    layoutMotion?.valueAt(nowMs)
                        ?: if (tileLayout == TileLayout.FULLSCREEN) 1f else 0f
                val margin = CARD_MARGIN_PX.toFloat()
                val slide = tileSlideFraction * (outWidth - appLeft) * (1f - expanded)
                val mainLen =
                    if (splitVertical) {
                        outHeight - if (dockAtBottom) dockH else 0
                    } else {
                        outWidth - if (dockAtBottom) 0 else dockW
                    }
                val tileEdge =
                    if (tileEdgeOverride > 0f) {
                        tileEdgeOverride
                    } else {
                        (if (splitVertical) 0f else appLeft) +
                            VirtualLauncher.dashSplitGeom(mainLen, VirtualLauncher.currentDashSplitFraction()).tileEdge
                    }
                val mapPane = VirtualLauncher.mapPaneBounds()
                val splitLeft = mapPane.left - slide
                val splitRight = mapPane.right - slide
                val splitBottom = outHeight - mapPane.bottom

                fun mix(
                    from: Float,
                    to: Float,
                ) = from + (to - from) * expanded
                drawInputUV(
                    map!!,
                    mix(splitLeft, appLeft),
                    mix(splitRight, outWidth.toFloat()),
                    mix(splitBottom, appBottom),
                    mix(outHeight - mapPane.top, outHeight.toFloat()),
                    cornerRadius = TILE_CORNER_RADIUS * (1f - expanded),
                )
            }
            if (showMediaPane &&
                !tilesSuppressed &&
                media.hasContent &&
                tileLayout == TileLayout.SPLIT &&
                (VirtualLauncher.dashboardMode || VirtualLauncher.dashboardPageAnimating)
            ) {
                val pane = VirtualLauncher.mediaAppBounds()
                val slide = tileSlideFraction * (outWidth - appLeft)
                drawInputUV(
                    media,
                    pane.left - slide,
                    pane.right - slide,
                    outHeight - pane.bottom,
                    outHeight - pane.top,
                    cornerRadius = TILE_CORNER_RADIUS,
                )
            }
            // 页点压在地图/媒体卡之上（固定不随 tile 滑动，与 GridActivity 原位置一致）
            val dotsAlpha = pageDotsAlphaAt(nowMs)
            if (VirtualLauncher.pageDotsOnTop() && dotsAlpha > 0.001f) {
                syncOverlayTexture(pageDotsSlot, pageDots)?.let { dots ->
                    drawOverlay(pageDotsSlot, dots.left, dots.top, dots.right, dots.bottom, dotsAlpha)
                }
            }
            if (assistLayerVisible && assistLayer.hasContent) {
                // 预乘 alpha 混合：虚拟屏空白处 alpha=0，只有浮层像素压在卡片上
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                drawInput(assistLayer, appLeft, outWidth.toFloat(), appBottom, outHeight.toFloat())
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            if (dock.hasContent) {
                if (dockAtBottom) {
                    drawInput(dock, 0f, outWidth.toFloat(), 0f, dockH.toFloat())
                } else {
                    drawInput(dock, 0f, dockW.toFloat(), 0f, outHeight.toFloat())
                }
            }
            // 叠加卡片最后绘制（最上层，不受分支影响）：卡片坐标为输出画面坐标（顶部原点）
            syncOverlayTexture(overlaySlot, overlayCard)?.let { card ->
                drawOverlay(overlaySlot, card.left, card.top, card.right, card.bottom, card.alpha)
            }
            syncOverlayTexture(assistiveDotSlot, assistiveDot)?.let { dot ->
                drawOverlay(assistiveDotSlot, dot.left, dot.top, dot.right, dot.bottom, dot.alpha)
            }
            // 截图请求：读回刚合成好的这一帧（即推给车机的画面），在交换缓冲之前读
            captureRequest?.let { callback ->
                captureRequest = null
                callback(readOutputFrame())
            }
            if (fullRedraw ||
                overlayDirty ||
                dock.hasContent ||
                app.hasContent ||
                hi?.hasContent == true ||
                map?.hasContent == true
            ) {
                EGL14.eglSwapBuffers(display, outputSurface)
            }
            overlayDirty = false
        }

        // 初始黑帧（只清屏，不碰空纹理）+ 交付输入 Surface + 放行 start()
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, outputSurface)
        onSurfacesReady?.invoke(dock.surface, app.surface, hi?.surface, map?.surface, media.surface)
        startLatch?.countDown()

        // ---- 合成循环 ----
        // 有输入新帧：按目标帧率（1000/targetFps）节流合成。
        // 无新帧（界面静止，如启动器/阅读页/地图停靠）：以 33ms 心跳重复合成最后一帧内容——
        // 否则编码器无输入、视频流停摆，接收端画面冻结（表现为"桌面卡死/不可交互"，
        // 2026-10-09 实测踩坑：触摸其实送到了，只是没有帧能反映出来）。
        try {
            var lastComposeAt = System.currentTimeMillis()
            while (running.get()) {
                // 快车道（60fps 节流）只由「会被画进输出画面」的输入驱动：
                // dock + 当前活动应用屏（app/hi 二选一）+ tile 可见时的地图屏。
                // 高德走地图屏、竖屏应用走超采样屏——只看 dock/原生屏会漏掉它们，
                // 60fps 档被 33ms 静止心跳锁死在 30fps。
                // 非活动屏的新帧不在判定内（画面不含它）：帧仍会在 composeFrame 里
                // 持续消费（纹理保持最新，避免 SurfaceTexture 队列积压卡住后台应用），
                // 但不为看不见的内容白烧 GPU/带宽。
                val hasNew =
                    isLayoutTransitioning() ||
                        pageDotsFading(android.os.SystemClock.uptimeMillis()) ||
                        (assistLayerVisible && assistLayerInput?.hasNewFrame == true) ||
                        overlayDirty ||
                        dock.hasNewFrame ||
                        (if (activeAppHi) appHi else app)?.hasNewFrame == true ||
                        (tileLayout != TileLayout.HIDDEN && mapInput?.hasNewFrame == true) ||
                        (showMediaPane && tileLayout == TileLayout.SPLIT && mediaInput?.hasNewFrame == true)
                val now = System.currentTimeMillis()
                val intervalMs = if (hasNew) 1000L / targetFps.coerceIn(1, 120) else 33L
                val waitMs = intervalMs - (now - lastComposeAt)
                if (waitMs <= 0) {
                    composeFrame(fullRedraw = false)
                    lastComposeAt = now
                } else {
                    // 等到下一个出帧点，或被新帧/状态变化提前叫醒（此前固定 sleep(8) 轮询：
                    // 静止时每秒白醒 ~125 次，且新帧最多晚 8ms 才被发现）
                    synchronized(frameSignal) { frameSignal.wait(waitMs) }
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            for (slot in arrayOf(overlaySlot, pageDotsSlot, assistiveDotSlot)) {
                if (slot.texture != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(slot.texture), 0) }
                    slot.texture = 0
                }
                slot.uploaded = null
            }
            runCatching { media.surface.release() }
            runCatching { media.surfaceTexture.release() }
            mediaInput = null
            assistLayerInput = null
            runCatching { assistLayer.surface.release() }
            runCatching { assistLayer.surfaceTexture.release() }
            runCatching { dock.surface.release() }
            runCatching { app.surface.release() }
            runCatching { dock.surfaceTexture.release() }
            runCatching { app.surfaceTexture.release() }
            map?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            hi?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            }
            runCatching { EGL14.eglDestroySurface(display, outputSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    /** [oes]=true：外部纹理采样（SurfaceTexture）；false：普通 2D 采样（壁纸位图）。 */
    private fun buildProgram(oes: Boolean): Int {
        val fragment =
            if (oes) {
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                uniform int uClipEnabled;
                uniform vec4 uClipRect;
                uniform float uClipRadius;
                void main() {
                    if (uClipEnabled == 1) {
                        vec2 p = gl_FragCoord.xy;
                        vec2 half2 = vec2(uClipRadius);
                        vec2 c = clamp(p, uClipRect.xy + half2, uClipRect.zw - half2);
                        if (distance(p, c) > uClipRadius) discard;
                    }
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
                """.trimIndent()
            } else {
                """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                uniform float uAlpha;
                void main() {
                    // 位图是预乘 alpha：四个通道同乘才是正确的整体淡化
                    gl_FragColor = texture2D(uTexture, vTexCoord) * uAlpha;
                }
                """.trimIndent()
            }
        val vertex =
            """
            attribute vec2 aPos;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
            """.trimIndent()

        fun compile(
            type: Int,
            source: String,
        ): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            // 编译失败必须留痕并抛出：静默继续 = 黑屏无从排查（EGL 有 checkEgl，GLSL 这层此前漏了）
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "GLSL 编译失败（type=$type）：${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("GLSL compile type=$type")
            }
            return shader
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        return program
    }

    /**
     * 每次绘制的顶点/纹理坐标写进这两块**复用**的直接缓冲（仅 GL 线程用）。此前每次
     * draw 都 allocateDirect 两块新缓冲——每帧 6~10 次直接内存分配，靠 Cleaner 回收，
     * 是合成线程上的稳定 GC 压力。glDrawArrays 调用时即读取客户端数组，复用安全。
     */
    private val posBuf = floatBuffer(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val texBuf = floatBuffer(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    private fun vertexBuffer(
        buffer: FloatBuffer,
        vararg values: Float,
    ): FloatBuffer {
        buffer.clear()
        buffer.put(values)
        buffer.position(0)
        return buffer
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun checkEgl(
        ok: Boolean,
        stage: String,
    ) {
        if (!ok) {
            Log.e(TAG, "EGL 失败：$stage")
            throw IllegalStateException("EGL $stage")
        }
    }
}

package io.github.ezn24.droidcar.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Places a smaller picture inside a larger encoder surface, the rest black. Android Auto head units offer a
 * resolution plus margins (a 1920x720 screen as 1920x1080 with 360 rows of margin) and show only the middle: the
 * launcher is drawn at the visible size into [input] and this copies each frame into the middle of [output].
 * One full-screen texture draw per frame on its own GL thread; the compositor is untouched.
 */
class InsetSurface(
    private val output: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    private val left: Int,
    private val top: Int,
    private val innerWidth: Int,
    private val innerHeight: Int,
    private val log: (String) -> Unit,
) {
    private val thread = HandlerThread("inset-surface").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var program = 0
    private var textureId = 0
    private val matrix = FloatArray(16)
    private val quad: FloatBuffer =
        ByteBuffer
            .allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                // x, y, u, v
                put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
                position(0)
            }

    /** The surface the launcher draws into (innerWidth x innerHeight); null when GL setup failed. */
    @Volatile var input: Surface? = null
        private set

    fun start(): Surface? {
        val ready = CountDownLatch(1)
        handler.post {
            runCatching { setUp() }.onFailure { log("INSET: GL setup failed - ${it.message}") }
            ready.countDown()
        }
        ready.await(3, TimeUnit.SECONDS)
        return input
    }

    fun release() {
        handler.post {
            runCatching { texture?.release() }
            texture = null
            runCatching { input?.release() }
            input = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        context =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }
        program = link(VERTEX, FRAGMENT)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val st = SurfaceTexture(textureId).apply { setDefaultBufferSize(innerWidth, innerHeight) }
        st.setOnFrameAvailableListener({ draw() }, handler)
        texture = st
        input = Surface(st)
        log("INSET: ${innerWidth}x$innerHeight placed at $left,$top in ${outWidth}x$outHeight")
    }

    private fun draw() {
        val st = texture ?: return
        st.updateTexImage()
        st.getTransformMatrix(matrix)
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // GL's origin is bottom-left
        GLES20.glViewport(left, outHeight - top - innerHeight, innerWidth, innerHeight)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, st.timestamp)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun link(
        vs: String,
        fs: String,
    ): Int {
        fun compile(
            type: Int,
            src: String,
        ): Int =
            GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src)
                GLES20.glCompileShader(it)
            }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private companion object {
        const val VERTEX =
            "attribute vec4 aPos; attribute vec4 aUv; uniform mat4 uTex; varying vec2 vUv;" +
                "void main() { gl_Position = aPos; vUv = (uTex * aUv).xy; }"
        const val FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 vUv; uniform samplerExternalOES uSrc;" +
                "void main() { gl_FragColor = texture2D(uSrc, vUv); }"
    }
}

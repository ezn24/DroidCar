package io.github.ezn24.cardeck.neo.launcher

import android.content.Context
import io.github.ezn24.cardeck.neo.shizuku.Shell

/**
 * Chat mode (2026-11-17): when a chat app is opened on the head unit, lower the density of its display so the shortest side is about 610dp,
 * which makes the app think it is on a tablet and use the two-pane layout (list left, conversation right). Restored when leaving the chat
 * app.
 *
 * Why it works: the head-unit app display 1752x720@267dpi converts to 1050x431dp; the shortest side 431dp < 600dp,
 * so apps always use the stretched phone layout. WhatsApp switches to the tablet layout at 190dpi (606dp), measured. Cost: text is about
 * 28% smaller.
 * Only CarDeck's own virtual display changes; the phone screen is unaffected, and virtual displays are rebuilt each session so nothing
 * leaks across sessions.
 *
 * Every method runs shell commands and must be called on a background thread.
 */
object ChatMode {
    /**
     * Chat apps that have a tablet / wide layout and survive a density change while the process is running.
     *
     * Deliberately excluded (head-unit tests 2026-11-18): WeChat - it adapts density itself, and after lowering density the title bar
     * duplicates and proportions break;
     * Discord (React Native) - after a density change glyphs render as tofu boxes. Neither supports changing density at runtime,
     * so they keep the original density (for WeChat, enable its in-app "landscape mode" to widen it).
     */
    val PACKAGES =
        setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "jp.naver.line.android",
            "com.facebook.orca",
            "com.instagram.android",
            "org.thoughtcrime.securesms",
            "com.kakao.talk",
            // Not chat apps but also have a tablet layout (measured at 188dpi 2026-11-18: ChatGPT sidebar + conversation, YouTube
            // multi-column grid)
            "com.openai.chatgpt",
            "com.google.android.youtube",
        )

    /**
     * Apps that measure text with the phone-screen density but draw it with the host display density (React Native). Whenever the
     * head-unit density differs from the phone's,
     * the text is clipped in half (Discord measured 2026-11-18: original density, tablet density and cold start all broke the same way;
     * only a density equal to the phone's works). Fix [matchPhone]: enlarge the display's **logical size** to the size that keeps the same
     * dp at "density = phone density",
     * and SurfaceFlinger scales it back to the original buffer - the app sees an unchanged dp layout (a wide display gets the
     * tablet two-pane), and measuring and drawing densities agree. Touch coordinates are multiplied by [scaleFor]. Independent of the
     * chat-mode switch (this is a rendering fix).
     */
    val PHONE_DENSITY_PACKAGES = setOf("com.discord")

    /** Target shortest side: slightly above the 600dp tablet threshold, with a small margin. */
    private const val TARGET_SMALLEST_DP = 610f

    @Volatile
    private var loweredDisplay = -1

    /** Logical / physical pixel ratio of the display while [matchPhone] is in effect; 1 when not. */
    @Volatile
    private var displayScale = 1f

    /** Touch injection ratio: stream coordinates -> logical coordinates of that display. */
    fun scaleFor(displayId: Int): Float = if (displayId >= 0 && displayId == loweredDisplay) displayScale else 1f

    fun enabled(context: Context): Boolean = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("chatMode", true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("chatMode", enabled)
            .apply()
    }

    /** Called before an app is opened on [displayId]: chat app -> lower density; other apps -> restore this display. */
    fun beforeLaunch(
        context: Context,
        displayId: Int,
        packageName: String,
        log: (String) -> Unit,
    ) {
        if (displayId < 0) return
        if (packageName in PHONE_DENSITY_PACKAGES) {
            matchPhone(displayId, packageName, log)
        } else if (enabled(context) && packageName in PACKAGES) {
            lower(displayId, packageName, log)
        } else if (loweredDisplay == displayId) {
            restore(log)
        }
    }

    /** Called on returning to desktop / dashboard / closing settings: removes the chat density. */
    fun restore(log: (String) -> Unit) {
        val display = loweredDisplay
        if (display < 0) return
        loweredDisplay = -1
        val resized = displayScale != 1f
        displayScale = 1f
        if (resized) Shell.exec("wm size reset -d $display", timeoutMs = 3_000L)
        Shell.exec("wm density reset -d $display", timeoutMs = 3_000L)
        log("CHAT: restored density of display $display")
    }

    /** Session end: the virtual displays are released with it, so only state is cleared. */
    fun forget() {
        loweredDisplay = -1
        displayScale = 1f
        physicalCache.clear()
        phoneDensityCache = 0
    }

    /**
     * Physical size/density of each display do not change within a session, so look it up once and cache it (each `wm` call is a shell
     * round trip;
     * previously opening a chat app first ran 2-3 of them, directly slowing the app launch). Display IDs are rebuilt every session;
     * [forget] clears the cache.
     */
    private val physicalCache = java.util.concurrent.ConcurrentHashMap<Int, Triple<Int, Int, Int>>()

    @Volatile
    private var phoneDensityCache = 0

    /** (width, height, physical density); null if unreadable. */
    private fun physicalOf(displayId: Int): Triple<Int, Int, Int>? {
        physicalCache[displayId]?.let { return it }
        val size = Shell.exec("wm size -d $displayId", timeoutMs = 3_000L).output
        val dims = Regex("""Physical size: (\d+)x(\d+)""").find(size) ?: return null
        val density = Shell.exec("wm density -d $displayId", timeoutMs = 3_000L).output
        val physical =
            Regex("""Physical density: (\d+)""")
                .find(density)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        return Triple(dims.groupValues[1].toInt(), dims.groupValues[2].toInt(), physical)
            .also { physicalCache[displayId] = it }
    }

    /**
     * Effective density of the phone's main display (changes if the user edits "display size", but is basically constant in a session, so
     * cached per session).
     */
    private fun phoneDensity(): Int? {
        if (phoneDensityCache > 0) return phoneDensityCache
        val out = Shell.exec("wm density", timeoutMs = 3_000L).output
        val value =
            (
                Regex("""Override density: (\d+)""").find(out)
                    ?: Regex("""Physical density: (\d+)""").find(out)
            )?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        phoneDensityCache = value
        return value
    }

    private fun matchPhone(
        displayId: Int,
        packageName: String,
        log: (String) -> Unit,
    ) {
        // The display is already at phone density (switching from one RN app to another): nothing to do
        if (loweredDisplay == displayId && displayScale > 1f) return
        if (loweredDisplay >= 0) restore(log)
        val phone = phoneDensity() ?: return
        val (width, height, physical) = physicalOf(displayId) ?: return
        val scale = phone.toFloat() / physical
        if (scale <= 1.01f) return
        // Even dimensions (consistent with the stream-size convention)
        val logicalW = (width * scale).toInt() and 1.inv()
        val logicalH = (height * scale).toInt() and 1.inv()
        Shell.exec("wm size ${logicalW}x$logicalH -d $displayId", timeoutMs = 3_000L)
        Shell.exec("wm density $phone -d $displayId", timeoutMs = 3_000L)
        displayScale = logicalW.toFloat() / width
        loweredDisplay = displayId
        log(
            "CHAT: $packageName -> display $displayId logical ${width}x$height->${logicalW}x$logicalH " +
                "density $physical->$phone (= phone, touch x${"%.2f".format(displayScale)})",
        )
    }

    private fun lower(
        displayId: Int,
        packageName: String,
        log: (String) -> Unit,
    ) {
        // The display is already at tablet density (switching between chat apps): do not set again
        if (loweredDisplay == displayId && displayScale == 1f) return
        if (loweredDisplay >= 0) restore(log)
        val (width, height, physical) = physicalOf(displayId) ?: return
        val shortSide = minOf(width, height)
        val target = (shortSide * 160f / TARGET_SMALLEST_DP).toInt()
        // Already wide enough (high-resolution head unit): leave it alone
        if (target >= physical) return
        Shell.exec("wm density $target -d $displayId", timeoutMs = 3_000L)
        loweredDisplay = displayId
        log("CHAT: $packageName -> display $displayId density $physical->$target (shortest ~${(shortSide * 160 / target)}dp, tablet)")
    }
}

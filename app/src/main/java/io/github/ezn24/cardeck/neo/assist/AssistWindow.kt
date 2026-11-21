package io.github.ezn24.cardeck.neo.assist

/** Snapshot of the voice assistant window (Gemini / Google Assistant overlay) state. */
data class AssistWindow(
    val visible: Boolean,
    val displayId: Int = 0,
    val taskId: Int = -1,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val hasFrame: Boolean get() = right > left && bottom > top
}

/**
 * Parses `dumpsys window windows` to find the voice assistant window. Both forms must be recognized (2026-11-15 S24 Ultra tests):
 *
 * - Classic VoiceInteractionSession: `ty=VOICE_INTERACTION` (excluding the `_STARTING` placeholder window);
 * - **The Gemini overlay is a plain Activity**: `googlequicksearchbox/...assistant...FloatyActivity`,
 * `ty=BASE_APPLICATION`. It opens on the **currently focused display**: if the user just tapped on the car, it opens straight on
 * the car virtual display (native glow + "Ask Gemini" bar), only covered by the dashboard cards.
 *
 * Visibility: 12+ has `isVisible=true` / `isOnScreen=true`; earlier versions rely on `mHasSurface=true` plus
 * `mViewVisibility=0x0`. Returns the first **visible** assistant window; visible=false if none is visible.
 */
fun parseAssistWindow(dump: String): AssistWindow {
    var fallback: AssistWindow? = null
    for (block in dump.split(Regex("\\n\\s*Window #\\d+ "))) {
        val header = block.substringBefore('\n')
        if (!VOICE_TYPE.containsMatchIn(block) && !ASSIST_ACTIVITY.containsMatchIn(header)) continue
        val displayId =
            DISPLAY_ID
                .find(block)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 0
        val taskId =
            TASK_ID
                .find(block)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: -1
        val visible =
            block.contains("isVisible=true") ||
                block.contains("isOnScreen=true") ||
                (block.contains("mHasSurface=true") && block.contains("mViewVisibility=0x0"))
        val frame = FRAME.find(block) ?: OLD_FRAME.find(block)
        val window =
            if (frame == null) {
                AssistWindow(visible, displayId, taskId)
            } else {
                val (l, t, r, b) = frame.destructured
                AssistWindow(visible, displayId, taskId, l.toInt(), t.toInt(), r.toInt(), b.toInt())
            }
        if (visible) return window
        if (fallback == null) fallback = window
    }
    return fallback ?: AssistWindow(false)
}

private val VOICE_TYPE = Regex("\\bty=VOICE_INTERACTION(?!_STARTING)\\b")

/** Known assistant overlay Activities (window title = package/class). */
private val ASSIST_ACTIVITY =
    Regex(
        "com\\.google\\.android\\.googlequicksearchbox/\\S*(?:assistant|Floaty)" +
            "|com\\.google\\.android\\.apps\\.bard/\\S*(?:Overlay|Floaty)",
    )
private val DISPLAY_ID = Regex("\\bmDisplayId=(\\d+)")
private val TASK_ID = Regex("\\btaskId=(\\d+)")
private val FRAME = Regex("(?<![A-Za-z])frame=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
private val OLD_FRAME = Regex("\\bmFrame=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

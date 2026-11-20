package io.github.ezn24.cardeck.neo.assist

/** 语音助理窗口（Gemini / Google 助理浮层）的状态快照。 */
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
 * 解析 `dumpsys window windows`，找语音助理窗口。两种形态都要认（2026-11-15 S24 Ultra 实测）：
 *
 *  - 经典 VoiceInteractionSession：`ty=VOICE_INTERACTION`（排除 `_STARTING` 启动占位窗）；
 *  - **Gemini 浮层是普通 Activity**：`googlequicksearchbox/...assistant...FloatyActivity`，
 *    `ty=BASE_APPLICATION`。它开在**当前焦点屏**——用户刚在车机上点过，就直接开在
 *    车机虚拟屏上（原生光效 + 「問 Gemini」条），只是被仪表盘卡片盖住。
 *
 * 可见性：12+ 有 `isVisible=true` / `isOnScreen=true`；更早靠 `mHasSurface=true` 加
 * `mViewVisibility=0x0`。返回第一个**可见**的助理窗口；都不可见时返回 visible=false。
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

/** 已知的助理浮层 Activity（窗口标题 = 包名/类名）。 */
private val ASSIST_ACTIVITY =
    Regex(
        "com\\.google\\.android\\.googlequicksearchbox/\\S*(?:assistant|Floaty)" +
            "|com\\.google\\.android\\.apps\\.bard/\\S*(?:Overlay|Floaty)",
    )
private val DISPLAY_ID = Regex("\\bmDisplayId=(\\d+)")
private val TASK_ID = Regex("\\btaskId=(\\d+)")
private val FRAME = Regex("(?<![A-Za-z])frame=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
private val OLD_FRAME = Regex("\\bmFrame=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

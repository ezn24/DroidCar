package io.github.ezn24.cardeck.neo.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * "Forced landscape not in effect" hint: probing + geometry computation + hint card drawing.
 *
 * Scenario (reproduced on a real device 2026-10-18): the app is already running, and after connecting to the head unit am compat writes
 * forced landscape.
 * `am compat` does kill the process, but when the system restores the task the ActivityRecord is still
 * rebuilt with the old portrait configuration (`displayChangedWithoutRestart=true`), so the app keeps running as a letterboxed portrait
 * window.
 * The only fix is force-stop then relaunch - the hint card lets the user do it in one tap.
 *
 * All probe data comes from `dumpsys activity activities` (no defaults, no estimates):
 *   `bounds=Rect(...)`        the four edges of the letterbox inner window (the app's actual window)
 *   `mMaxBounds=Rect(...)`    the letterbox outer frame (usable area of the virtual display)
 * Their difference is the left/right (top/bottom on a portrait head unit) black bars. The system also gives `isLetterboxRunning`
 * and `letterboxReason`, so we hint only for "letterbox caused by an orientation lock",
 * without bothering apps the user wants in a portrait window, or misreporting aspect-ratio-type letterboxes.
 */

/** Simple integer rectangle (android.graphics.Rect is a stub in JVM unit tests, so it is avoided). */
internal data class PixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Relevant facts about the top Activity of one display in `dumpsys activity activities`. */
internal data class LetterboxActivity(
    val packageName: String,
    /** In `package/class` form, usable directly in `am start -n`. */
    val component: String,
    val displayId: Int,
    val letterboxed: Boolean,
    val letterboxReason: String?,
    /** App window rectangle (`bounds=`). */
    val inner: PixelBounds,
    /** Usable-area rectangle of that display (`mMaxBounds=`; when missing, the display size given by the caller is used). */
    val full: PixelBounds,
)

private val displayRegex = Regex("""^Display #(\d+) \(activities""")
private val topActivityRegex =
    Regex("""topResumedActivity=ActivityRecord\{(\w+) u\d+ (\S+)/(\S+) t\d+\}""")
private val historyRegex =
    Regex("""^\s*\* Hist\s+#\d+: ActivityRecord\{(\w+) u\d+ (\S+)/(\S+) t\d+\}""")
private val boundsRegex = Regex("""^\s+bounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")
private val maxBoundsRegex = Regex("""mMaxBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

/**
 * Parses the letterbox facts of the top Activity of the given virtual display.
 *
 * @param displayId target display
 * @param dump raw output of `dumpsys activity activities`
 * @param fallbackW/[fallbackH] display-size fallback when `mMaxBounds` is missing (facts held by the caller,
 *        not constant defaults - the left/right black bars can still be computed)
 */
internal fun parseTopLetterboxActivity(
    displayId: Int,
    dump: String,
    fallbackW: Int,
    fallbackH: Int,
): LetterboxActivity? {
    if (dump.isBlank()) return null
    val lines = dump.lineSequence().toList()
    var sectionStart = -1
    var sectionEnd = lines.size
    for (index in lines.indices) {
        val match = displayRegex.find(lines[index]) ?: continue
        if (sectionStart < 0) {
            if (match.groupValues[1].toInt() == displayId) sectionStart = index
        } else {
            sectionEnd = index
            break
        }
    }
    if (sectionStart < 0) return null
    // Take only the target display's section, so Hist blocks of other displays are not pulled in
    val section = lines.subList(sectionStart + 1, sectionEnd)
    // Top task: the first topResumedActivity in the section (tasks are printed from top to bottom)
    var topHash: String? = null
    var topPkg: String? = null
    var topCls: String? = null
    for (line in section) {
        val match = topActivityRegex.find(line) ?: continue
        topHash = match.groupValues[1]
        topPkg = match.groupValues[2]
        topCls = match.groupValues[3]
        break
    }
    // Locate the Hist block of the top ActivityRecord: prefer an exact hash match,
    // and when the ROM omits topResumedActivity take the first Hist block
    var block = -1
    var header: MatchResult? = null
    for (index in section.indices) {
        val match = historyRegex.find(section[index]) ?: continue
        if (topHash == null || match.groupValues[1] == topHash) {
            block = index
            header = match
            break
        }
    }
    if (block < 0 && topHash == null) {
        for (index in section.indices) {
            val match = historyRegex.find(section[index]) ?: continue
            block = index
            header = match
            break
        }
    }
    if (block < 0) return null
    val pkg = header!!.groupValues[2].ifBlank { topPkg ?: return null }
    val cls = header.groupValues[3].ifBlank { topCls ?: return null }
    if (pkg.isBlank() || cls.isBlank()) return null
    var letterboxed = false
    var reason: String? = null
    var inner: PixelBounds? = null
    var full: PixelBounds? = null
    for (index in block + 1 until section.size) {
        val line = section[index]
        if (historyRegex.containsMatchIn(line) || line.trimStart().startsWith("* Task")) break
        if (line.trimStart().startsWith("Resumed activities")) break
        if (line.contains("isLetterboxRunning=true")) letterboxed = true
        if (reason == null) {
            Regex("""letterboxReason=(\w+)""").find(line)?.let { reason = it.groupValues[1] }
        }
        if (inner == null) {
            boundsRegex.find(line)?.let { match ->
                inner =
                    PixelBounds(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(),
                    )
            }
        }
        if (full == null) {
            maxBoundsRegex.find(line)?.let { match ->
                full =
                    PixelBounds(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(),
                    )
            }
        }
    }
    val outer = full ?: PixelBounds(0, 0, fallbackW, fallbackH)
    val window = inner ?: outer
    if (outer.width <= 0 || outer.height <= 0) return null
    return LetterboxActivity(
        packageName = pkg,
        component = "$pkg/$cls",
        displayId = displayId,
        letterboxed = letterboxed,
        letterboxReason = reason,
        inner = window,
        full = outer,
    )
}

/**
 * Whether the "forced landscape not in effect" hint is worth showing.
 *
 * Four conditions must hold together: the top app is on the "app landscape" list, is really letterboxed,
 * the reason is FIXED_ORIENTATION (orientation lock), and the user has not dismissed the hint this session.
 */
internal fun shouldShowLetterboxHint(
    activity: LetterboxActivity,
    forceLandscapePackages: Set<String>,
    dismissedPackages: Set<String>,
): Boolean =
    activity.letterboxed &&
        activity.letterboxReason == "FIXED_ORIENTATION" &&
        activity.packageName in forceLandscapePackages &&
        activity.packageName !in dismissedPackages

internal enum class LetterboxSide { LEFT, RIGHT }

/**
 * Chooses which side to place the hint card: prefer the left (near the driver); if the left cannot fit and the right is wider, use the
 * right.
 * [neededPx] = minimum card width + outer margins on both sides (measured from the text, not a fixed threshold).
 * If neither side is enough -> null (the bars are too narrow, no different from full-screen landscape, and the hint would only cram
 * together).
 */
internal fun chooseLetterboxSide(
    leftPx: Float,
    rightPx: Float,
    neededPx: Float,
): LetterboxSide? =
    when {
        leftPx >= neededPx && leftPx >= rightPx -> LetterboxSide.LEFT
        rightPx >= neededPx -> LetterboxSide.RIGHT
        leftPx >= neededPx -> LetterboxSide.LEFT
        else -> null
    }

internal enum class LetterboxCardState { NORMAL, BUSY, FAILED }

/**
 * Gesture arbitration for the hint card (pure function, the single decision point of TouchRouter).
 *
 * The card is drawn on the black bar and never injected into the app; once a press hits it, the whole gesture belongs to the card:
 *  - lift point still inside the card -> counts as a tap;
 *  - move out of the card then lift -> cancel (avoids accidental taps);
 *  - while held, move events are not leaked to the app (otherwise the list underneath would be dragged).
 */
internal enum class CardGesture { NOT_HANDLED, CONSUME, TAP }

internal fun resolveCardGesture(
    cardVisible: Boolean,
    holding: Boolean,
    down: Boolean,
    hit: Boolean,
    appGestureActive: Boolean = false,
): CardGesture =
    when {
        // Card already collapsed: hand back to normal routing regardless of whether it was held before
        !cardVisible -> if (holding) CardGesture.CONSUME else CardGesture.NOT_HANDLED
        // While held: swallow every move during the press; on lift decide the tap by whether it is still inside the card
        holding && down -> CardGesture.CONSUME
        holding -> if (hit) CardGesture.TAP else CardGesture.CONSUME
        // Not held: take over only if "already on the card at press time". If an app gesture in progress is dragged into the card area,
        // do not take over - otherwise the app never receives the lift and stays in the pressed state (stuck).
        down && hit && !appGestureActive -> CardGesture.CONSUME
        else -> CardGesture.NOT_HANDLED
    }

/**
 * Hint card drawing (offscreen Bitmap -> the compositor pastes it as a texture onto the black bar).
 *
 * All sizes are scaled by canvas density; **the minimum width is measured from the text** (button text + padding),
 * with no hard-coded threshold like "do not show if the bar is under N px" - when too narrow to fit the copy it simply is not shown.
 */
internal object LetterboxCardPainter {
    private const val MAX_WIDTH_DP = 200f
    private const val MARGIN_DP = 6f
    private const val PAD_H_DP = 8f
    private const val PAD_V_DP = 10f
    private const val GAP_DP = 7f

    /** Button height converges with the font size (32dp + 9.5sp would look hollow inside the button). */
    private const val BUTTON_H_DP = 28f
    private const val RADIUS_DP = 10f
    private const val TITLE_SP = 11.5f
    private const val BODY_SP = 9.5f

    /**
     * Button font size matches the body text: 11.5sp looked more eye-catching than the title on a real device (user feedback, 2026-10-18).
     */
    private const val BUTTON_SP = 9.5f
    private const val BUTTON_PAD_H_DP = 8f

    fun marginPx(density: Float): Float = MARGIN_DP * density

    /** Maximum card width (even with wide bars it does not fill them, so it does not become a giant billboard in a large bar). */
    fun maxWidthPx(density: Float): Float = MAX_WIDTH_DP * density

    /** Minimum card width: determined by the measured widths of the button text and title. */
    fun minWidthPx(density: Float): Float {
        val button = textPaint(BUTTON_SP * density, Color.WHITE, bold = true)
        val buttonW = button.measureText("强制重启") + 2f * BUTTON_PAD_H_DP * density
        val title = textPaint(TITLE_SP * density, Color.WHITE, bold = true)
        // Title must fit at least 5 CJK characters (any narrower and it breaks into single characters per line)
        val titleW = title.measureText("横屏未生效")
        return 2f * PAD_H_DP * density + maxOf(buttonW, titleW)
    }

    /**
     * Builds the hint card bitmap; [widthPx] is determined by the black bar width.
     * Returns null when the width cannot fit the copy or the height cannot fit the button (the caller then does not show it).
     */
    fun render(
        density: Float,
        widthPx: Int,
        maxHeightPx: Int,
        state: LetterboxCardState,
    ): Bitmap? {
        if (widthPx <= 0 || maxHeightPx <= 0) return null
        val width = widthPx.toFloat()
        val textWidth = width - 2f * PAD_H_DP * density
        if (textWidth <= 0f) return null
        if (width < minWidthPx(density)) return null
        val (titleText, bodyText, buttonText) =
            when (state) {
                LetterboxCardState.NORMAL -> Triple("横屏未生效", "仍是竖屏窗口", "强制重启")
                LetterboxCardState.BUSY -> Triple("正在重启", "将以横屏打开", "请稍候")
                LetterboxCardState.FAILED -> Triple("仍然竖屏", "可能不支持", "关闭")
            }
        val titlePaint = textPaint(TITLE_SP * density, TITLE_COLOR, bold = true)
        val bodyPaint = textPaint(BODY_SP * density, BODY_COLOR, bold = false)
        val title = staticLayout(titleText, titlePaint, textWidth.toInt())
        val body = staticLayout(bodyText, bodyPaint, textWidth.toInt())
        val height =
            (
                2f * PAD_V_DP * density + title.height + GAP_DP * density +
                    body.height + GAP_DP * density + BUTTON_H_DP * density
            ).toInt()
        if (height > maxHeightPx) return null
        val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = RADIUS_DP * density
        val bounds = RectF(0f, 0f, widthPx.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, fillPaint(BG_COLOR))
        canvas.drawRoundRect(bounds, radius, radius, strokePaint(BORDER_COLOR, density))
        var y = PAD_V_DP * density
        canvas.save()
        canvas.translate(PAD_H_DP * density, y)
        title.draw(canvas)
        canvas.restore()
        y += title.height + GAP_DP * density
        canvas.save()
        canvas.translate(PAD_H_DP * density, y)
        body.draw(canvas)
        canvas.restore()
        y += body.height + GAP_DP * density
        val buttonW = width - 2f * PAD_H_DP * density
        val buttonH = BUTTON_H_DP * density
        val buttonRect =
            RectF(PAD_H_DP * density, y, PAD_H_DP * density + buttonW, y + buttonH)
        val enabled = state == LetterboxCardState.NORMAL
        canvas.drawRoundRect(
            buttonRect,
            buttonH / 2f,
            buttonH / 2f,
            fillPaint(if (enabled) ACCENT_COLOR else DISABLED_FILL),
        )
        val buttonPaint =
            textPaint(
                BUTTON_SP * density,
                if (enabled) BUTTON_TEXT_COLOR else DISABLED_TEXT,
                bold = true,
            ).apply {
                // drawText is left-aligned by default: without CENTER the whole string starts at buttonRect.centerX()
                // and overflows the button to the right (exposed by a real-device screenshot, 2026-10-18)
                textAlign = Paint.Align.CENTER
            }
        val metrics = buttonPaint.fontMetrics
        val textY =
            buttonRect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(buttonText, buttonRect.centerX(), textY, buttonPaint)
        return bitmap
    }

    private fun staticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
    ): StaticLayout =
        StaticLayout
            .Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()

    private fun textPaint(
        sizePx: Float,
        color: Int,
        bold: Boolean,
    ): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

    private fun strokePaint(
        color: Int,
        density: Float,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            this.color = color
        }

    // Dark card + amber accent, matching the dock's dark style; contrast is sufficient on the black bar
    private const val BG_COLOR = 0xF0171C22.toInt()
    private const val BORDER_COLOR = 0x33FFFFFF
    private const val ACCENT_COLOR = 0xFFFFC24B.toInt()
    private const val TITLE_COLOR = 0xFFFFC24B.toInt()
    private const val BODY_COLOR = 0xB3FFFFFF.toInt()
    private const val BUTTON_TEXT_COLOR = 0xFF1B1405.toInt()
    private const val DISABLED_FILL = 0x33FFFFFF
    private const val DISABLED_TEXT = 0xB3FFFFFF.toInt()
}

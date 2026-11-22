package io.github.ezn24.droidcar.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.ezn24.droidcar.iap2.RouteGuidanceUpdate
import io.github.ezn24.droidcar.nav.NavInfo
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Turn card for the alternate (cluster) screen: maneuver arrow, distance and the road after the maneuver, drawn from
 * the same navigation reading that feeds iAP2 route guidance. Drawn with the platform fonts, so Chinese road names
 * render (unlike a receiver's own HUD font).
 */
object AltScreenCard {
    fun render(
        info: NavInfo,
        width: Int,
        height: Int,
    ): Bitmap? {
        if (width < 32 || height < 32) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = min(width, height) * 0.08f
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 22, 24, 30) }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, background)

        val wide = width > height * 1.3f
        val iconSize = if (wide) height * 0.62f else min(width * 0.55f, height * 0.42f)
        val pad = min(width, height) * 0.08f
        val iconLeft = if (wide) pad else (width - iconSize) / 2f
        val iconTop = if (wide) (height - iconSize) / 2f else pad
        drawManeuver(canvas, info, RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize))

        val textLeft = if (wide) iconLeft + iconSize + pad else pad
        val textRight = width - pad
        val textWidth = (textRight - textLeft).coerceAtLeast(1f)
        val distance =
            info.toManeuver?.let { d -> listOf(d.text, unitName(d.unit)).filter { it.isNotBlank() }.joinToString(" ") }
        val road = info.afterRoad.ifBlank { info.instruction }
        val distancePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = (if (wide) height * 0.3f else height * 0.16f)
            }
        val roadPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, 220, 224, 232)
                textSize = (if (wide) height * 0.16f else height * 0.085f)
            }
        fit(distancePaint, distance.orEmpty(), textWidth)
        val roadText = ellipsize(roadPaint, road, textWidth)
        if (wide) {
            val block = distancePaint.textSize + (if (roadText.isNotEmpty()) roadPaint.textSize * 1.3f else 0f)
            var baseline = (height - block) / 2f + distancePaint.textSize * 0.85f
            distance?.let { canvas.drawText(it, textLeft, baseline, distancePaint) }
            baseline += roadPaint.textSize * 1.4f
            if (roadText.isNotEmpty()) canvas.drawText(roadText, textLeft, baseline, roadPaint)
        } else {
            distancePaint.textAlign = Paint.Align.CENTER
            roadPaint.textAlign = Paint.Align.CENTER
            var baseline = iconTop + iconSize + pad + distancePaint.textSize * 0.85f
            distance?.let { canvas.drawText(it, width / 2f, baseline, distancePaint) }
            baseline += roadPaint.textSize * 1.5f
            if (roadText.isNotEmpty()) canvas.drawText(roadText, width / 2f, baseline, roadPaint)
        }
        return bitmap
    }

    private fun unitName(unit: Int): String =
        when (unit) {
            RouteGuidanceUpdate.UNIT_KM -> "km"
            RouteGuidanceUpdate.UNIT_MILES -> "mi"
            RouteGuidanceUpdate.UNIT_METERS -> "m"
            RouteGuidanceUpdate.UNIT_YARDS -> "yd"
            RouteGuidanceUpdate.UNIT_FEET -> "ft"
            else -> ""
        }

    private fun fit(
        paint: Paint,
        text: String,
        width: Float,
    ) {
        while (paint.textSize > 8f && paint.measureText(text) > width) paint.textSize *= 0.92f
    }

    private fun ellipsize(
        paint: Paint,
        text: String,
        width: Float,
    ): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("…") > width) end--
        return if (end <= 0) "" else text.substring(0, end) + "…"
    }

    /** Generic arrow: a stem from the bottom that bends towards the exit angle; U-turn, roundabout and arrival get their own shapes. */
    private fun drawManeuver(
        canvas: Canvas,
        info: NavInfo,
        box: RectF,
    ) {
        val stroke = box.width() * 0.13f
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val cx = box.centerX()
        val bottom = box.bottom - stroke
        val type = info.maneuverType
        if (type == RouteGuidanceUpdate.TYPE_ARRIVE) {
            canvas.drawCircle(cx, box.centerY() - box.height() * 0.08f, box.width() * 0.22f, paint)
            canvas.drawCircle(cx, box.centerY() - box.height() * 0.08f, box.width() * 0.07f, fill)
            canvas.drawLine(cx, box.centerY() + box.height() * 0.14f, cx, bottom, paint)
            return
        }
        if (type == RouteGuidanceUpdate.TYPE_U_TURN) {
            val r = box.width() * 0.2f
            val right = cx + r
            val left = cx - r
            val top = box.top + box.height() * 0.3f
            val path =
                Path().apply {
                    moveTo(right, bottom)
                    lineTo(right, top + r)
                    arcTo(RectF(left, top, right, top + 2 * r), 0f, -180f)
                    lineTo(left, box.centerY() + r)
                }
            canvas.drawPath(path, paint)
            arrowHead(canvas, fill, left, box.centerY() + r * 1.6f, 180.0, box.width())
            return
        }
        val roundabout =
            info.junction == 1 || type == RouteGuidanceUpdate.TYPE_ENTER_ROUNDABOUT || type == RouteGuidanceUpdate.TYPE_EXIT_ROUNDABOUT
        val angle = (info.exitAngle ?: RouteGuidanceUpdate.exitAngleFor(type) ?: 0).toDouble()
        val pivotY = box.centerY() + box.height() * 0.05f
        if (roundabout) {
            canvas.drawCircle(cx, pivotY - box.height() * 0.05f, box.width() * 0.18f, paint)
        }
        val radians = Math.toRadians(angle)
        val length = box.height() * 0.36f
        val tipX = (cx + sin(radians) * length).toFloat()
        val tipY = (pivotY - cos(radians) * length).toFloat()
        val path =
            Path().apply {
                moveTo(cx, bottom)
                lineTo(cx, pivotY)
                lineTo(tipX, tipY)
            }
        canvas.drawPath(path, paint)
        arrowHead(canvas, fill, tipX, tipY, angle, box.width())
    }

    private fun arrowHead(
        canvas: Canvas,
        paint: Paint,
        x: Float,
        y: Float,
        angleDegrees: Double,
        size: Float,
    ) {
        val head = size * 0.2f
        val r = Math.toRadians(angleDegrees)
        val dx = sin(r).toFloat()
        val dy = -cos(r).toFloat()
        // Tip ahead of the stem end, base corners perpendicular to the direction
        val tipX = x + dx * head * 0.6f
        val tipY = y + dy * head * 0.6f
        val baseX = x - dx * head * 0.5f
        val baseY = y - dy * head * 0.5f
        val path =
            Path().apply {
                moveTo(tipX, tipY)
                lineTo(baseX - dy * head * 0.7f, baseY + dx * head * 0.7f)
                lineTo(baseX + dy * head * 0.7f, baseY - dx * head * 0.7f)
                close()
            }
        canvas.drawPath(path, paint)
    }
}

package app.dropswift

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Round progress ring with the percent in the middle. [progress] runs from 0 to [max]. */
class RingView(context: Context) : View(context) {

    var max = 1000

    var progress = 0
        set(value) {
            val v = value.coerceIn(0, max)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    private val density = context.resources.displayMetrics.density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14 * density
        color = Color.parseColor("#1E2A40")
    }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14 * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E68A")
    }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val box = RectF()

    /** Small word above the percent, e.g. "TRANSFERRING...". */
    var caption = ""
        set(value) {
            field = value
            invalidate()
        }

    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA9BD")
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val pad = arc.strokeWidth / 2 + 2 * density
        val left = (width - size) / 2 + pad
        val top = (height - size) / 2 + pad
        box.set(left, top, left + size - 2 * pad, top + size - 2 * pad)
        canvas.drawArc(box, 0f, 360f, false, track)
        val sweep = if (max > 0) 360f * progress / max else 0f
        if (sweep > 0f) canvas.drawArc(box, -90f, sweep, false, arc)
        label.textSize = size * 0.22f
        label.color = Color.parseColor("#00E68A")
        val pct = if (max > 0) progress * 100 / max else 0
        val y = height / 2f - (label.descent() + label.ascent()) / 2 + size * 0.04f
        canvas.drawText("$pct%", width / 2f, y, label)
        if (caption.isNotEmpty()) {
            small.textSize = size * 0.065f
            canvas.drawText(caption, width / 2f, y - size * 0.2f, small)
        }
    }
}

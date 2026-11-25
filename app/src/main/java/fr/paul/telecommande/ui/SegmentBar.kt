package fr.paul.telecommande.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import fr.paul.telecommande.R
import fr.paul.telecommande.youtube.Segment

/** Barre de progression de la vidéo YouTube, avec les passages sponsorisés en vert. */
class SegmentBar(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.track) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt() }
    private val r = RectF()

    var duration = 0.0
    var position = 0.0
        set(value) { field = value; invalidate() }
    var segments: List<Segment> = emptyList()
        set(value) { field = value; invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (10 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val h = 4 * d
        r.set(0f, cy - h / 2, width.toFloat(), cy + h / 2)
        canvas.drawRoundRect(r, h, h, track)
        if (duration <= 0) return
        r.right = (width * (position / duration)).toFloat().coerceIn(0f, width.toFloat())
        canvas.drawRoundRect(r, h, h, fill)
        segments.forEach {
            val x1 = (width * (it.start / duration)).toFloat(); val x2 = (width * (it.end / duration)).toFloat()
            r.set(x1, cy - h, maxOf(x2, x1 + 3 * d), cy + h)
            canvas.drawRoundRect(r, d, d, seg)
        }
    }
}

package fr.paul.telecommande.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import fr.paul.telecommande.R

/** Anneau de progression (temps du jour / limite) avec un grand chiffre au centre. */
class RingView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val stroke = 11 * d
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = ContextCompat.getColor(context, R.color.track)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted); textAlign = Paint.Align.CENTER; textSize = 12 * d
    }
    private val r = RectF()

    /** 0..1 ; au-delà de 1 l'anneau passe en rouge. Négatif = pas de limite (anneau décoratif plein). */
    var ratio = -1f
        set(value) { field = value; invalidate() }
    var center = ""
        set(value) { field = value; invalidate() }
    var caption = ""
        set(value) { field = value; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (128 * d).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val half = stroke / 2
        r.set(half, half, width - half, height - half)
        canvas.drawArc(r, 0f, 360f, false, track)
        val over = ratio > 1f
        if (over) {
            arc.shader = null; arc.color = ContextCompat.getColor(context, R.color.danger)
        } else {
            arc.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                ContextCompat.getColor(context, R.color.accent), ContextCompat.getColor(context, R.color.accent2), Shader.TileMode.CLAMP)
        }
        val sweep = when { ratio < 0f -> 0f; else -> 360f * ratio.coerceIn(0f, 1f) }
        if (sweep > 0f) canvas.drawArc(r, -90f, maxOf(sweep, 4f), false, arc)
        big.textSize = (if (center.length > 6) 20 else 24) * d
        val cy = height / 2f
        canvas.drawText(center, width / 2f, cy + big.textSize * 0.2f, big)
        if (caption.isNotBlank()) canvas.drawText(caption, width / 2f, cy + big.textSize * 0.2f + 18 * d, small)
    }
}

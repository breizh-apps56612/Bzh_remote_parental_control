package fr.paul.telecommande.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import fr.paul.telecommande.R

/** Histogramme simple : temps de TV par jour (7 jours), jour courant en couleur d'accent. */
class BarChartView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.bar) }
    private val barToday = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.danger); strokeWidth = d; pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.muted); textSize = 11 * d; textAlign = Paint.Align.CENTER }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink); textSize = 10 * d; textAlign = Paint.Align.CENTER }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface) }
    private val r = RectF()

    var labels: List<String> = emptyList()
    var valuesMin: List<Int> = emptyList()
        set(v) { field = v; contentDescription = labels.zip(v).joinToString(", ") { "${it.first} : ${it.second} minutes" }; invalidate() }
    var limitMin = 0
    var showValues = true
    var highlight = -1   // index mis en avant (-1 = dernier)
    var heightDp = 150

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (heightDp * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (valuesMin.isEmpty()) return
        val top = 22 * d; val bottom = height - 20 * d
        val max = maxOf(valuesMin.maxOrNull() ?: 0, limitMin, 60).toFloat()
        val slot = width / valuesMin.size.toFloat()
        val bw = minOf(slot * 0.55f, 28 * d)
        // 1) la limite d'abord, en fond : elle ne barre jamais un chiffre
        if (limitMin > 0) {
            val y = bottom - (bottom - top) * (limitMin / max)
            canvas.drawLine(0f, y, width.toFloat(), y, limitPaint)
        }
        // 2) les barres
        val tops = FloatArray(valuesMin.size)
        valuesMin.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val h = (bottom - top) * (v / max)
            r.set(cx - bw / 2, bottom - maxOf(h, 2 * d), cx + bw / 2, bottom)
            tops[i] = r.top
            val hot = i == (if (highlight >= 0) highlight else valuesMin.lastIndex)
            if (hot) barToday.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom,
                ContextCompat.getColor(context, R.color.accent2), ContextCompat.getColor(context, R.color.accent), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, minOf(8 * d, bw / 2.5f), minOf(8 * d, bw / 2.5f), if (hot) barToday else bar)
            canvas.drawText(labels.getOrElse(i) { "" }, cx, height - 4 * d, label)
        }
        // 3) les valeurs par-dessus, sur une pastille de la couleur de la carte (lisibles même sur la ligne)
        if (showValues) valuesMin.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val cx = slot * i + slot / 2
            val t = if (v >= 60) "${v / 60}h${"%02d".format(v % 60)}" else "${v}m"
            val tw = value.measureText(t)
            val by = tops[i] - 5 * d
            r.set(cx - tw / 2 - 4 * d, by - value.textSize, cx + tw / 2 + 4 * d, by + 3 * d)
            canvas.drawRoundRect(r, 6 * d, 6 * d, chip)
            canvas.drawText(t, cx, by, value)
        }
    }
}

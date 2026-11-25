package fr.paul.telecommande.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import fr.paul.telecommande.R
import fr.paul.telecommande.core.AppCatalog
import kotlin.math.min

/**
 * Camembert (anneau) de répartition du temps par appli, total au centre.
 * Les 5 applis principales ont chacune leur part, le reste est regroupé dans « Autres ».
 */
class PieChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Slice(val key: String, val label: String, val value: Long, val color: Int)

    var slices: List<Slice> = emptyList()
        set(v) { field = v; invalidate() }
    var centerTitle = ""
    var centerSub = ""
    var heightDp = 220

    private val d = resources.displayMetrics.density
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ContextCompat.getColor(context, R.color.track) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink); textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.muted); textAlign = Paint.Align.CENTER }
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (heightDp * d).toInt().coerceAtMost(w)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val stroke = size * 0.16f
        val r = size / 2f - stroke / 2f - 2 * d
        val cx = width / 2f; val cy = height / 2f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        arc.strokeWidth = stroke; track.strokeWidth = stroke
        canvas.drawCircle(cx, cy, r, track)

        val total = slices.sumOf { it.value }.toFloat()
        if (total > 0) {
            var start = -90f
            slices.forEach { s ->
                val sweep = s.value / total * 360f
                arc.color = s.color
                canvas.drawArc(rect, start, sweep, false, arc)
                start += sweep
            }
            // fins séparateurs entre les parts
            if (slices.size > 1) {
                gapPaint.color = ContextCompat.getColor(context, R.color.surface); gapPaint.strokeWidth = 2.5f * d
                start = -90f
                slices.forEach { s ->
                    val a = Math.toRadians(start.toDouble())
                    val ri = r - stroke / 2; val ro = r + stroke / 2
                    canvas.drawLine(cx + (ri * Math.cos(a)).toFloat(), cy + (ri * Math.sin(a)).toFloat(),
                        cx + (ro * Math.cos(a)).toFloat(), cy + (ro * Math.sin(a)).toFloat(), gapPaint)
                    start += s.value / total * 360f
                }
            }
        }
        big.textSize = size * 0.14f; small.textSize = 12 * d
        canvas.drawText(centerTitle, cx, cy + big.textSize * 0.25f, big)
        if (centerSub.isNotEmpty()) canvas.drawText(centerSub, cx, cy + big.textSize * 0.25f + 18 * d, small)
    }

    companion object {
        /** Couleurs bien distinctes pour les applis sans couleur de marque (ou en double). */
        private val PALETTE = intArrayOf(
            0xFF8B6CFF.toInt(), 0xFF0EA5E9.toInt(), 0xFFF59E0B.toInt(), 0xFF10B981.toInt(),
            0xFFEC4899.toInt(), 0xFF8B5CF6.toInt(), 0xFF14B8A6.toInt(), 0xFFF97316.toInt()
        )
        private const val GREY = 0xFF6B7280.toInt()
        const val OTHERS = "__autres__"

        /** Deux couleurs trop proches à l'œil (ex. rouge Netflix et rouge YouTube) ? */
        private fun close(a: Int, b: Int): Boolean {
            val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
            val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
            val db = (a and 255) - (b and 255)
            return dr * dr + dg * dg + db * db < 110 * 110
        }

        /** Top 5 + « Autres », avec une couleur unique par part (couleur de marque si connue). */
        fun slicesFor(map: Map<String, Long>, name: (String) -> String = AppCatalog::name): List<Slice> {
            val sorted = map.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            val top = sorted.take(5)
            val rest = sorted.drop(5).sumOf { it.value }
            val used = mutableSetOf<Int>()
            var p = 0
            val out = top.map { (pkg, ms) ->
                var col = AppCatalog.brand(pkg).first
                if (col == GREY || used.any { close(it, col) }) {
                    while (p < PALETTE.size * 2 && used.any { close(it, PALETTE[p % PALETTE.size]) }) p++
                    col = PALETTE[p % PALETTE.size]
                }
                used += col
                Slice(pkg, name(pkg), ms, col)
            }.toMutableList()
            if (rest > 0) out += Slice(OTHERS, "Autres", rest, 0xFF9CA3AF.toInt())
            return out
        }
    }
}

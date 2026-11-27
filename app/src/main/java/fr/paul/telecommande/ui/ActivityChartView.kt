package fr.paul.telecommande.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import fr.paul.telecommande.R
import java.util.Calendar

/**
 * Un seul cadre de diagramme, trois modes (onglet Activité) :
 * - JOURNÉE : chronologie, une ligne par appli, chaque séance à sa place exacte ;
 * - HORAIRES : minutes regardées dans chaque heure de la journée, empilées par appli ;
 * - SEMAINE : temps de chaque jour, empilé par appli, avec la limite du contrat en pointillés.
 * Toucher un élément envoie sa valeur exacte à [onPick]. La hauteur est la même dans les trois modes.
 */
class ActivityChartView(context: Context) : View(context) {

    enum class Mode { DAY, HOURS, WEEK }

    /** Une séance : groupe d'appli (0..3, 3 = « Autres »), début et fin en millisecondes. */
    data class Sess(val group: Int, val start: Long, val end: Long)

    var mode = Mode.DAY
        set(v) { field = v; pick = null; invalidate(); announce() }
    var sessions: List<Sess> = emptyList()
        set(v) { field = v; invalidate(); announce() }
    var groupNames: List<String> = emptyList()
    /** Début (minuit) d'aujourd'hui, et des 7 jours affichés en mode Semaine (du plus ancien à aujourd'hui). */
    var dayStart = 0L
    var weekStarts: List<Long> = emptyList()
    var weekLabels: List<String> = emptyList()
    /** Limite par jour du contrat, en minutes (0 = pas de limite). */
    var limitMin = 0
    var onPick: ((String) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun col(id: Int) = ContextCompat.getColor(context, id)
    private val ink = col(R.color.ink)
    private val muted = col(R.color.muted)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; color = muted }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1 * d; color = 0xFFEEEFF6.toInt() }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * d; color = col(R.color.danger); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val r = RectF()
    /** Zones touchables du dessin en cours : rectangle → texte exact à afficher. */
    private val hits = mutableListOf<Pair<RectF, Pair<Any, String>>>()
    private var pick: Any? = null

    init { isClickable = true; isFocusable = true }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (232 * d).toInt())

    // ---------- Calculs (publics pour les tests) ----------

    /** Minutes de chaque groupe entre a et b (séances coupées aux bornes). */
    fun minutes(a: Long, b: Long): IntArray {
        val out = LongArray(GROUPS)
        sessions.forEach { s -> val o = minOf(s.end, b) - maxOf(s.start, a); if (o > 0) out[s.group] += o }
        return IntArray(GROUPS) { ((out[it] + 30_000) / 60_000).toInt() }
    }

    /** Plage horaire de la chronologie : 7 h – 23 h, élargie aux séances du jour. */
    fun dayRange(): Pair<Int, Int> {
        val today = sessions.filter { it.end > dayStart && it.start < dayStart + DAY }
        var h0 = 7; var h1 = 23
        today.forEach { s ->
            h0 = minOf(h0, ((maxOf(s.start, dayStart) - dayStart) / HOUR).toInt())
            h1 = maxOf(h1, ((minOf(s.end, dayStart + DAY) - dayStart + HOUR - 1) / HOUR).toInt())
        }
        return h0.coerceIn(0, 23) to h1.coerceIn(h0 + 1, 24)
    }

    // ---------- Dessin ----------

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        when (mode) {
            Mode.DAY -> drawDay(canvas)
            Mode.HOURS -> drawHours(canvas)
            Mode.WEEK -> drawWeek(canvas)
        }
    }

    private fun drawDay(c: Canvas) {
        val (h0, h1) = dayRange()
        val a = dayStart + h0 * HOUR; val b = dayStart + h1 * HOUR
        val left = 70 * d; val right = width - 4 * d
        val used = (0 until GROUPS).filter { g -> sessions.any { it.group == g && it.end > a && it.start < b } }
        val lanes = used.ifEmpty { listOf(0) }
        val laneH = 28 * d; val gap = 8 * d
        val top = ((height - 22 * d) - lanes.size * laneH - (lanes.size - 1) * gap) / 2
        val span = (b - a).toFloat()
        // Repères verticaux toutes les 3 h
        text.textAlign = Paint.Align.CENTER
        var t = h0 + (3 - h0 % 3) % 3
        while (t <= h1) {
            val x = left + (right - left) * ((t - h0) * HOUR) / span
            c.drawLine(x, top - 4 * d, x, top + lanes.size * (laneH + gap) - gap + 4 * d, grid)
            c.drawText("${t % 24} h", x, height - 6 * d, text)
            t += 3
        }
        lanes.forEachIndexed { i, g ->
            val y = top + i * (laneH + gap)
            text.textAlign = Paint.Align.LEFT; text.color = ink
            c.drawText(ellipsize(groupNames.getOrElse(g) { "" }, left - 10 * d), 0f, y + laneH / 2 + 4 * d, text)
            text.color = muted
            p.color = 0xFFF3F4FA.toInt(); r.set(left, y, right, y + laneH); c.drawRoundRect(r, 6 * d, 6 * d, p)
            sessions.filter { it.group == g && it.end > a && it.start < b }.forEach { s ->
                val x1 = left + (right - left) * (maxOf(s.start, a) - a) / span
                val x2 = maxOf(left + (right - left) * (minOf(s.end, b) - a) / span, x1 + 3 * d)
                r.set(x1, y + 3 * d, x2, y + laneH - 3 * d)
                p.color = COLORS[g]; c.drawRoundRect(r, 4 * d, 4 * d, p)
                if (pick == s) outline(c, r, COLORS[g])
                val minutes = ((s.end - s.start + 30_000) / 60_000).toInt()
                hits += RectF(x1 - 6 * d, y, x2 + 6 * d, y + laneH) to (s to "${groupNames.getOrElse(g) { "" }} · ${hm(s.start)} → ${hm(s.end)} · ${fmt(minutes)}")
            }
        }
        if (used.isEmpty()) { text.textAlign = Paint.Align.CENTER; c.drawText("Rien de regardé aujourd'hui", (left + right) / 2, top + laneH / 2 + 4 * d, text) }
    }

    private fun drawHours(c: Canvas) {
        val (h0, h1) = dayRange()
        val n = h1 - h0
        val left = 30 * d; val right = width.toFloat(); val bottom = height - 22 * d; val top = 10 * d
        val plotH = bottom - top
        yAxis(c, listOf(0, 15, 30, 45, 60), 60f, left, right, top, bottom) { "$it" }
        val slot = (right - left) / n
        val barW = minOf(slot * 0.62f, 14 * d)
        text.textAlign = Paint.Align.CENTER
        for (i in 0 until n) {
            val h = h0 + i
            val m = minutes(dayStart + h * HOUR, dayStart + (h + 1) * HOUR)
            val cx = left + slot * (i + 0.5f)
            if (pick == h) { p.color = 0x144B48E0; r.set(cx - slot / 2, top, cx + slot / 2, bottom); c.drawRoundRect(r, 4 * d, 4 * d, p) }
            stack(c, m, cx, barW, bottom, plotH / 60f)
            if (h % 3 == 0) c.drawText("$h h", cx, height - 6 * d, text)
            val parts = m.indices.filter { m[it] > 0 }.joinToString(" · ") { "${groupNames.getOrElse(it) { "" }} ${it.let { g -> m[g] }} min" }
            hits += RectF(cx - slot / 2, top, cx + slot / 2, bottom) to (h to
                "$h h – ${h + 1} h : " + if (parts.isEmpty()) "rien regardé" else "$parts (total ${m.sum()} min)")
        }
    }

    private fun drawWeek(c: Canvas) {
        val days = weekStarts.map { minutes(it, it + DAY) }
        val maxMin = maxOf(days.maxOfOrNull { it.sum() } ?: 0, limitMin, 60)
        val step = if (maxMin <= 180) 30 else 60
        val yMax = ((maxMin * 1.12f / step).toInt() + 1) * step
        val left = 38 * d; val right = width.toFloat(); val bottom = height - 22 * d; val top = 20 * d
        val plotH = bottom - top
        yAxis(c, (0..yMax step step).toList(), yMax.toFloat(), left, right, top, bottom) { hmMin(it) }
        if (limitMin > 0) {
            val y = bottom - plotH * limitMin / yMax
            c.drawLine(left, y, right, y, dash)
            text.textAlign = Paint.Align.RIGHT; text.color = col(R.color.danger)
            c.drawText("Contrat ${hmMin(limitMin)}", right, y - 4 * d, text); text.color = muted
        }
        val slot = (right - left) / maxOf(days.size, 1)
        val barW = minOf(slot * 0.56f, 26 * d)
        days.forEachIndexed { i, m ->
            val cx = left + slot * (i + 0.5f)
            if (pick == i) { p.color = 0x144B48E0; r.set(cx - slot / 2, top - 16 * d, cx + slot / 2, bottom); c.drawRoundRect(r, 6 * d, 6 * d, p) }
            val h = stack(c, m, cx, barW, bottom, plotH / yMax)
            text.textAlign = Paint.Align.CENTER
            if (m.sum() > 0) { text.color = ink; c.drawText(fmtShort(m.sum()), cx, bottom - h - 4 * d, text); text.color = muted }
            c.drawText(weekLabels.getOrElse(i) { "" }, cx, height - 6 * d, text)
            val tot = m.sum()
            val over = if (limitMin > 0 && tot > limitMin) " (contrat dépassé de ${fmt(tot - limitMin)})" else ""
            val parts = m.indices.filter { m[it] > 0 }.joinToString(" · ") { "${groupNames.getOrElse(it) { "" }} ${fmt(m[it])}" }
            hits += RectF(cx - slot / 2, 0f, cx + slot / 2, bottom) to (i to
                "${weekLabels.getOrElse(i) { "" }} : ${fmt(tot)}$over" + if (parts.isEmpty()) "" else " — $parts")
        }
    }

    /** Barre empilée par groupe (2 dp d'écart, haut arrondi) ; renvoie sa hauteur. */
    private fun stack(c: Canvas, m: IntArray, cx: Float, w: Float, bottom: Float, perMin: Float): Float {
        var y = bottom
        val last = m.indices.lastOrNull { m[it] > 0 } ?: return 0f
        m.forEachIndexed { g, v ->
            if (v <= 0) return@forEachIndexed
            val h = maxOf(v * perMin, 2 * d)
            r.set(cx - w / 2, y - h, cx + w / 2, y - if (y < bottom) 2 * d else 0f)
            p.color = COLORS[g]
            if (g == last) { c.drawRoundRect(r, 4 * d, 4 * d, p); c.drawRect(r.left, r.top + 4 * d, r.right, r.bottom, p) } else c.drawRect(r, p)
            y -= h
        }
        return bottom - y
    }

    private fun yAxis(c: Canvas, ticks: List<Int>, max: Float, left: Float, right: Float, top: Float, bottom: Float, label: (Int) -> String) {
        text.textAlign = Paint.Align.RIGHT
        ticks.forEach { v ->
            val y = bottom - (bottom - top) * v / max
            grid.color = if (v == 0) 0xFFC9CADD.toInt() else 0xFFEEEFF6.toInt()
            c.drawLine(left, y, right, y, grid)
            c.drawText(label(v), left - 5 * d, y + 4 * d, text)
        }
        grid.color = 0xFFEEEFF6.toInt()
    }

    private fun outline(c: Canvas, rr: RectF, color: Int) {
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; this.color = color }
        r.set(rr.left - 3 * d, rr.top - 3 * d, rr.right + 3 * d, rr.bottom + 3 * d)
        c.drawRoundRect(r, 6 * d, 6 * d, s)
    }

    private fun ellipsize(s: String, w: Float): String {
        if (text.measureText(s) <= w) return s
        var t = s; while (t.length > 1 && text.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    // ---------- Toucher et accessibilité ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(false)
        if (e.action == MotionEvent.ACTION_UP) {
            val hit = hits.firstOrNull { it.first.contains(e.x, e.y) }
                ?: hits.minByOrNull { kotlin.math.abs(it.first.centerX() - e.x) + kotlin.math.abs(it.first.centerY() - e.y) }
            if (hit != null) { pick = hit.second.first; onPick?.invoke(hit.second.second); invalidate() }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun announce() {
        val tot = when (mode) {
            Mode.WEEK -> weekStarts.sumOf { minutes(it, it + DAY).sum() }
            else -> minutes(dayStart, dayStart + DAY).sum()
        }
        contentDescription = when (mode) {
            Mode.DAY -> "Chronologie de la journée, ${fmt(tot)} regardées"
            Mode.HOURS -> "Minutes regardées par heure aujourd'hui, ${fmt(tot)} au total"
            Mode.WEEK -> "Temps par jour sur 7 jours, ${fmt(tot)} au total"
        }
    }

    companion object {
        const val GROUPS = 4
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        /** Couleurs fixes des applis (1re, 2e, 3e, autres), vérifiées pour les daltoniens sur fond blanc. */
        val COLORS = intArrayOf(0xFF4B48E0.toInt(), 0xFF0A9690.toInt(), 0xFFE8730C.toInt(), 0xFFB8338F.toInt())

        fun fmt(min: Int) = if (min >= 60) "${min / 60} h %02d".format(min % 60) else "$min min"
        fun fmtShort(min: Int) = if (min >= 60) "${min / 60}h%02d".format(min % 60) else "$min"
        fun hmMin(min: Int) = when { min == 0 -> "0"; min % 60 == 0 -> "${min / 60} h"; min < 60 -> "$min"; else -> "${min / 60} h ${min % 60}" }
        fun hm(ms: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = ms }
            return "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }
    }
}

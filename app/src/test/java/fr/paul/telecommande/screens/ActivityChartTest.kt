package fr.paul.telecommande.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import fr.paul.telecommande.ui.ActivityChartView
import fr.paul.telecommande.ui.ActivityChartView.Mode
import fr.paul.telecommande.ui.ActivityChartView.Sess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar

/**
 * Cadre de diagramme de l'onglet Activité (un cadre, trois modes) : calculs exacts, dessin sans plantage,
 * et valeur exacte affichée quand on touche un bloc, une heure ou un jour. Captures dans build/screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-notnight-xxhdpi")
class ActivityChartTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val day: Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 27, 0, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun at(h: Int, m: Int, dayOffset: Int = 0) = day + dayOffset * ActivityChartView.DAY + (h * 60 + m) * 60_000L

    private fun chart(): Pair<ActivityChartView, MutableList<String>> {
        val picks = mutableListOf<String>()
        val v = ActivityChartView(ctx).apply {
            sessions = listOf(
                Sess(0, at(7, 40), at(8, 2)), Sess(0, at(16, 45), at(17, 5)),
                Sess(1, at(18, 30), at(18, 55)), Sess(2, at(19, 58), at(20, 3)),
                // Mercredi (2 jours avant) : au-delà du contrat
                Sess(0, at(15, 0, -2), at(16, 10, -2)), Sess(1, at(17, 0, -2), at(17, 40, -2)),
                Sess(2, at(18, 0, -2), at(18, 15, -2)), Sess(3, at(18, 20, -2), at(18, 30, -2))
            )
            groupNames = listOf("Oqee", "YouTube", "Netflix", "Autres")
            dayStart = day; limitMin = 120
            weekStarts = (6 downTo 0).map { day - it * ActivityChartView.DAY }
            weekLabels = listOf("Sam", "Dim", "Lun", "Mar", "Mer", "Jeu", "Auj.")
            onPick = { picks += it }
        }
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v to picks
    }

    private fun render(v: View, name: String): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFFFFFFFF.toInt()); v.draw(Canvas(b))
        val dir = File(System.getenv("SCREENS_DIR") ?: "build/screens").apply { mkdirs() }
        File(dir, "activite-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test fun calculs_exacts() {
        val (v, _) = chart()
        assertArrayEquals(intArrayOf(42, 25, 5, 0), v.minutes(day, day + ActivityChartView.DAY))
        // Heure 7 h : 7:40 → 8:00 = 20 min ; heure 19 h : Netflix 2 min
        assertArrayEquals(intArrayOf(20, 0, 0, 0), v.minutes(at(7, 0), at(8, 0)))
        assertArrayEquals(intArrayOf(0, 0, 2, 0), v.minutes(at(19, 0), at(20, 0)))
        assertEquals(7 to 23, v.dayRange())
        assertEquals(135, v.minutes(at(0, 0, -2), at(0, 0, -1)).sum())
    }

    @Test fun trois_modes_dessines_et_touchables() {
        val (v, picks) = chart()
        for (m in Mode.values()) {
            v.mode = m
            val bmp = render(v, m.name.lowercase())
            assertTrue("dessin vide en mode $m", (0 until bmp.width step 7).any { x -> (0 until bmp.height step 7).any { y -> bmp.getPixel(x, y) != 0xFFFFFFFF.toInt() } })
            assertTrue("pas de description en mode $m", !v.contentDescription.isNullOrBlank())
        }
        // Semaine : toucher la barre du mercredi (5e sur 7) donne le détail exact et le dépassement
        v.mode = Mode.WEEK; render(v, "week")
        val left = 38 * ctx.resources.displayMetrics.density
        val slot = (v.width - left) / 7
        tap(v, left + slot * 4.5f, v.height * 0.7f)
        val wed = picks.last()
        assertTrue(wed, wed.startsWith("Mer : 2 h 15") && wed.contains("dépassé de 15 min") && wed.contains("Oqee 1 h 10"))
        // Journée : toucher n'importe où donne un horaire exact « h:mm → h:mm »
        v.mode = Mode.DAY; render(v, "day")
        tap(v, v.width * 0.75f, v.height * 0.4f)
        assertTrue(picks.last(), Regex("\\d{1,2}:\\d{2} → \\d{1,2}:\\d{2}").containsMatchIn(picks.last()))
        // Horaires : toucher une heure donne « h h – h h : … »
        v.mode = Mode.HOURS; render(v, "hours")
        tap(v, v.width * 0.5f, v.height * 0.5f)
        assertTrue(picks.last(), Regex("^\\d{1,2} h – \\d{1,2} h : ").containsMatchIn(picks.last()))
        render(v, "hours-touche")
    }
}

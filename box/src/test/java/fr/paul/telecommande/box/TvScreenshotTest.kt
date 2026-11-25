package fr.paul.telecommande.box

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import androidx.test.core.app.ApplicationProvider
import fr.paul.telecommande.shared.Profile
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Captures des VRAIS écrans de l'appli TV (code de production des fenêtres affichées par-dessus les applis),
 * rendus en 1920×1080 par Robolectric en mode graphique natif, sur une image de fond neutre.
 * Lancement : SCREENS=1 gradle :box:testReleaseUnitTest ; images dans le dossier SCREENS_DIR (ou box/build/screens).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-night-xhdpi")
class TvScreenshotTest {
    private val out = File(System.getenv("SCREENS_DIR") ?: "build/screens").apply { mkdirs() }
    private lateinit var o: Overlays

    @Before fun setUp() {
        assumeTrue(System.getenv("SCREENS") == "1")
        o = Overlays(ApplicationProvider.getApplicationContext()).apply { immediate = true }
    }

    /** Fond neutre « image de film » : ciel du soir et collines, pour voir l'effet du voile sombre. */
    private fun background(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF1E3A8A.toInt(), 0xFFF97316.toInt(), 0xFFFDE68A.toInt()), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.7f, h * 0.58f, h * 0.22f, intArrayOf(0xFFFFF7D6.toInt(), 0x00FFF7D6), null, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.7f, h * 0.58f, h * 0.22f, p)
        p.shader = null
        p.color = 0xFF3B2A5A.toInt(); c.drawOval(-w * 0.2f, h * 0.62f, w * 0.7f, h * 1.4f, p)
        p.color = 0xFF2A1E44.toInt(); c.drawOval(w * 0.35f, h * 0.7f, w * 1.3f, h * 1.5f, p)
    }

    private fun shot(file: String, fullscreen: Boolean = true, corner: Boolean = false) {
        val w = 1920; val h = 1080
        val v = o.lastRootForTest ?: error("aucune fenêtre")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        background(c, w, h)
        if (fullscreen) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h); v.draw(c)
        } else {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            val x = if (corner) w - v.measuredWidth - 64f else (w - v.measuredWidth) / 2f
            val y = if (corner) 64f else (h - v.measuredHeight) / 2f
            c.save(); c.translate(x, y); v.draw(c); c.restore()
        }
        File(out, "$file.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun timeUp() {
        o.showBlock("C'est fini pour aujourd'hui", "Temps de TV écoulé (1 h 30 par jour).", canRequest = true, requestMinutes = 15, pending = false, onParentCode = {}) {}
        shot("tv-01-temps-ecoule")
    }

    @Test fun setupFocus() {
        val a = org.robolectric.Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val btn = all(root).filterIsInstance<android.widget.TextView>().first { it.isFocusable && it.text.toString() in setOf("Rechercher", "Ouvrir") && it.text.toString() == "Rechercher" }
        btn.requestFocus(); btn.animate().cancel(); btn.scaleX = 1.06f; btn.scaleY = 1.06f
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "tv-07-reglages-focus.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun setupAndPairing() {
        val a = org.robolectric.Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "tv-08-activer.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        o.showPairingCode("482913", 120_000)
        shot("tv-09-code-appairage", fullscreen = false)
    }

    @Test fun whoWatches() {
        o.showWhoWatches(listOf(Profile("leo", "Léo", "🦖", 90), Profile("emma", "Emma", "🦄", 60), Profile("par", "Parents", "👑", 0, true)), "leo") {}
        shot("tv-02-qui-regarde")
        // carte avec le focus agrandie comme sur la vraie TV : elle ne doit pas être rognée
        fun focused(v: android.view.View): android.view.View? = if (v.isFocused) v else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { focused(g.getChildAt(it)) } }
        o.lastRootForTest?.let { focused(it) }?.apply { animate().cancel(); scaleX = 1.08f; scaleY = 1.08f }
        shot("tv-02b-qui-regarde-focus")
    }

    @Test fun pinPad() {
        o.showPinPadEx("Continuer la lecture (+30 min)", { null }, {}, {})
        shot("tv-03-code-parent")
    }

    @Test fun message() {
        o.showMessage("🍽️", "À table !", "Le repas est prêt.") {}
        shot("tv-04-message")
    }

    @Test fun timer() {
        o.showTimer("Encore 4 min", urgent = true)
        shot("tv-05-sablier", fullscreen = false, corner = true)
    }

    @Test fun stillThere() {
        o.showStillThere(30, {}, {})
        shot("tv-06-toujours-la")
    }
}

package fr.paul.telecommande.box

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import fr.paul.telecommande.shared.Updater
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-night-xhdpi")
class UpdateScreenTest {
    /** Notes longues : elles reviennent à la ligne et restent dans l'écran (rien de coupé à droite). */
    @Test fun longNotesStayOnScreen() {
        val a = Robolectric.buildActivity(UpdateActivity::class.java).setup().get()
        val notes = "TV : la TV ne se déclare plus comme lecteur d'écran (les applis ne lisent plus leurs menus à voix haute)."
        UpdateActivity::class.java.getDeclaredField("release").apply { isAccessible = true }.set(a, Updater.Release(200, "9.9.9", "u", "s", notes))
        UpdateActivity::class.java.getDeclaredField("state").apply { isAccessible = true }.set(a, "ready")
        UpdateActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(a)
        val rootView = a.window.decorView
        val w = 1920; val h = 1080
        rootView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        rootView.layout(0, 0, w, h)
        fun find(v: View): TextView? = if (v is TextView && v.text.toString() == notes) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val t = find(rootView)!!
        val loc = IntArray(2); t.getLocationInWindow(loc)
        val textW = t.paint.measureText(notes)
        assertTrue("lignes=${t.lineCount}", t.lineCount >= 2 || textW <= t.width)
        // Toutes les lignes tiennent dans la hauteur de la vue (rien de coupé en bas)
        assertTrue("hauteur ${t.height} < ${t.layout.height}", t.layout.height <= t.height - t.paddingTop - t.paddingBottom + 1)
        assertTrue("x=${loc[0]} w=${t.width}", loc[0] >= 0 && loc[0] + t.width <= w)
        android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { rootView.draw(android.graphics.Canvas(it))
            java.io.FileOutputStream("/tmp/update-screen.png").use { o -> it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o) } }
    }
}

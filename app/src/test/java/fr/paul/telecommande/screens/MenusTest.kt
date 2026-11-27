package fr.paul.telecommande.screens

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DeviceType
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.shared.Profile
import fr.paul.telecommande.ui.BoxDashboardActivity
import fr.paul.telecommande.ui.RemoteActivity
import fr.paul.telecommande.ui.SettingsActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Rangement des menus (Téléphone 2.6.6) : chaque menu ne mélange plus des choses de nature différente.
 * - Volet « Plus » de la télécommande : ce qu'on fait sur la TV, puis une seule entrée « Réglages et aide ».
 * - « Réglages et aide » : Réglages / En cas de souci ; plus de mise à jour ni de saisie d'adresse.
 * - Onglet Enfants : « Les règles de la maison » séparées de « Cette TV » (santé, données, appairage).
 * - Onglet Réglages : que des actions ; « Vos données » en note de bas de page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-notnight-xxhdpi")
class MenusTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun idle() { repeat(8) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(30) } }

    private fun layout(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 2400)
    }

    /** Tous les textes affichés, dans l'ordre de l'écran, en minuscules. */
    private fun texts(v: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(x: View) {
            if (x.visibility != View.VISIBLE) return
            (x as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it.lowercase() }
            (x as? ViewGroup)?.let { g -> (0 until g.childCount).forEach { walk(g.getChildAt(it)) } }
        }
        walk(v)
        return out
    }

    private fun find(v: View, label: String): View? {
        if ((v as? TextView)?.text?.toString() == label) return v
        return (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it), label) } }
    }

    private fun tap(v: View) {
        var x: View? = v
        while (x != null && !x.isClickable) x = x.parent as? View
        x!!.performClick(); idle()
    }

    private fun sheetTexts(): List<String> {
        val d = ShadowDialog.getLatestDialog()!!.window!!.decorView
        layout(d); return texts(d)
    }

    @Test fun reglagesSansMelange() {
        DeviceStore(ctx).save(Device(id = "salon", name = "TV du salon", type = DeviceType.ANDROID_TV, host = ""))
        val a: Activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        layout(a.window.decorView); idle()
        val t = texts(a.window.decorView)
        listOf("mes écrans", "enfants", "l'appli").forEach { assertTrue("Partie « $it » attendue : $t", it in t) }
        assertTrue("Mise à jour dans L'appli", "rechercher une mise à jour" in t)
        assertFalse("« Vos données » n'est plus une ligne du menu", "vos données restent chez vous" in t)
        assertTrue("« Vos données » en note de bas de page", t.any { it.startsWith("🔒 vos données restent chez vous") })
    }

    @Test fun voletPlusDeLaTelecommande() {
        val d = Device(id = "salon-menus", name = "TV du salon", type = DeviceType.ANDROID_TV, host = "10.0.0.2")
        DeviceStore(ctx).save(d)
        val a = Robolectric.buildActivity(RemoteActivity::class.java, Intent(ctx, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ID, d.id)).setup().get()
        layout(a.window.decorView); idle()
        a.javaClass.getDeclaredMethod("showMoreKeys").apply { isAccessible = true }.invoke(a); idle()
        val plus = sheetTexts()
        listOf("sur la tv", "autres touches", "cette télécommande", "réglages et aide").forEach { assertTrue("« $it » attendu dans Plus : $plus", it in plus) }
        listOf("aide", "réglages", "mise à jour", "adresse de l'appareil").forEach { assertFalse("« $it » ne doit plus être dans Plus : $plus", it in plus) }
        // Ouvrir « Réglages et aide » : deux parties distinctes
        tap(find(ShadowDialog.getLatestDialog()!!.window!!.decorView, "Réglages et aide")!!)
        val opt = sheetTexts()
        listOf("réglages", "en cas de souci", "réglages rapides", "diagnostic de connexion", "journal de diagnostic").forEach {
            assertTrue("« $it » attendu dans Réglages et aide : $opt", it in opt)
        }
        listOf("mise à jour", "adresse de l'appareil").forEach { assertFalse("« $it » ne doit plus être dans Réglages et aide : $opt", it in opt) }
        assertTrue("Réglages avant En cas de souci", opt.indexOf("réglages rapides") < opt.indexOf("diagnostic de connexion"))
    }

    @Test fun ongletEnfantsSepareRegleEtTechnique() {
        val d = Device(id = "salon-kids", name = "TV du salon", type = DeviceType.ANDROID_TV, host = "", boxFp = "ab12cd34")
        DeviceStore(ctx).save(d)
        val ps = ParentalStore(ctx)
        val r = ps.boxRules(d.id)
        r.enabled = true; r.dailyTotalMin = 120
        r.profiles = mutableListOf(Profile("leo", "Léo", "🦖", 90))
        r.version = 1
        ps.saveBoxRules(d.id, r)
        val a = Robolectric.buildActivity(BoxDashboardActivity::class.java, Intent(ctx, BoxDashboardActivity::class.java)
            .putExtra(BoxDashboardActivity.EXTRA_ID, d.id).putExtra(BoxDashboardActivity.EXTRA_MODE, BoxDashboardActivity.MODE_PARENTAL)).setup().get()
        fun set(name: String, v: Any?) {
            var c: Class<*>? = a.javaClass
            while (c != null) { if (runCatching { c!!.getDeclaredField(name).apply { isAccessible = true }.set(a, v) }.isSuccess) return; c = c.superclass }
        }
        set("status", org.json.JSONObject().put("interactive", true).put("todayTotal", 0L)
            .put("profile", org.json.JSONObject().put("id", "leo").put("emoji", "🦖").put("name", "Léo")))
        set("error", null)
        a.javaClass.getDeclaredMethod("render").apply { isAccessible = true }.invoke(a)
        layout(a.window.decorView); idle()
        val t = texts(a.window.decorView)
        val rules = t.indexOf("les règles de la maison"); val tv = t.indexOf("cette tv")
        assertTrue("Parties attendues : $t", rules >= 0 && tv > rules)
        assertFalse("Plus de partie « Toute la TV »", "toute la tv" in t)
        val all = t.indexOf("toutes les règles"); val health = t.indexOf("santé de la tv"); val unpair = t.indexOf("appairage")
        assertTrue("« Toutes les règles » dans Les règles : $t", all in (rules + 1) until tv)
        assertTrue("« Santé de la TV » et « Appairage » dans Cette TV : $t", health > tv && unpair > tv)
    }
}

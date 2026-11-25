package fr.paul.telecommande.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DeviceType
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.shared.AppRule
import fr.paul.telecommande.shared.Profile
import fr.paul.telecommande.ui.BoxDashboardActivity
import fr.paul.telecommande.ui.BoxRulesActivity
import fr.paul.telecommande.ui.RulesWizardActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Captures des VRAIS écrans de l'appli (code de production), rendus par Robolectric en mode graphique natif,
 * avec des données d'exemple. Sert aux visuels de présentation et à vérifier la mise en page.
 * Lancement : SCREENS=1 gradle :app:testReleaseUnitTest --tests (nom de cette classe) ; images dans app/build/screens
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-night-xxhdpi")
class ScreenshotTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val out = File(System.getenv("SCREENS_DIR") ?: "build/screens").apply { mkdirs() }
    private lateinit var device: Device

    @Before fun setUp() {
        assumeTrue(System.getenv("SCREENS") == "1")
        device = Device(id = "salon", name = "TV du salon", type = DeviceType.ANDROID_TV, host = "", boxFp = "ab12cd34")
        DeviceStore(ctx).save(device)
        val ps = ParentalStore(ctx)
        ps.setPin("2580")
        val r = ps.boxRules(device.id)
        r.enabled = true; r.dailyTotalMin = 120; r.bedtimeEnabled = true; r.bedtimeStart = 20 * 60 + 30; r.bedtimeEnd = 7 * 60
        r.profiles = mutableListOf(Profile("leo", "Léo", "🦖", 90), Profile("emma", "Emma", "🦄", 60), Profile("par", "Parents", "👑", 0, true))
        r.apps = mutableMapOf(
            "com.google.android.youtube.tv" to AppRule(limitMin = 45),
            "com.netflix.ninja" to AppRule(limitMin = 60),
            "com.twitch.android.app" to AppRule(blocked = true),
            "com.disney.disneyplus" to AppRule(days = 0b1100000, from = 14 * 60, to = 18 * 60)
        )
        r.version = 1
        ps.saveBoxRules(device.id, r)
    }

    private fun set(o: Any, name: String, v: Any?) {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            runCatching { c!!.getDeclaredField(name).apply { isAccessible = true }.set(o, v); return }
            c = c.superclass
        }
        error("champ $name introuvable")
    }

    private fun call(o: Any, name: String) = o.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(o)

    /** Dessine l'écran (et la boîte de dialogue ouverte s'il y en a une) à la taille d'un téléphone 1080×2400. */
    private fun shot(a: Activity, file: String, h: Int = 2400, withDialog: Boolean = false) {
        val w = 1080
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        root.draw(c)
        if (withDialog) ShadowDialog.getLatestDialog()?.window?.decorView?.let { d ->
            c.drawColor(0x99000000.toInt())
            val dw = (w * 0.86).toInt()
            d.measure(View.MeasureSpec.makeMeasureSpec(dw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
            d.layout(0, 0, dw, d.measuredHeight)
            c.save(); c.translate(((w - dw) / 2).toFloat(), ((h - d.measuredHeight) / 2).toFloat()); d.draw(c); c.restore()
        }
        File(out, "$file.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun intent(cls: Class<*>, key: String) = Intent(ctx, cls).putExtra(key, device.id)

    private val labels = JSONObject().put("com.netflix.ninja", "Netflix").put("com.google.android.youtube.tv", "YouTube")
        .put("com.disney.disneyplus", "Disney+").put("fr.francetv.pluzz", "france.tv").put("com.spotify.tv.android", "Spotify")

    private fun status(withRequest: Boolean) = JSONObject()
        .put("interactive", true).put("label", "Netflix")
        .put("profile", JSONObject().put("emoji", "🦖").put("name", "Léo"))
        .put("todayTotal", 72 * 60_000L)
        .put("today", JSONObject().put("com.netflix.ninja", 38 * 60_000L).put("com.google.android.youtube.tv", 27 * 60_000L).put("fr.francetv.pluzz", 7 * 60_000L))
        .put("labels", labels)
        .put("media", JSONObject().put("title", "Les Explorateurs du ciel").put("subtitle", "Saison 2 · Épisode 4").put("pkg", "com.netflix.ninja"))
        .apply { if (withRequest) put("pending", JSONArray().put(JSONObject().put("id", "r1").put("label", "YouTube").put("minutes", 15))) }

    private fun week(): JSONObject {
        val daily = JSONArray(listOf(95, 64, 118, 82, 101, 135, 72).map { it * 60_000L })
        val hourly = JSONArray((0 until 24).map { h ->
            when (h) { in 7..8 -> 12; in 12..13 -> 20; in 16..17 -> 55; in 18..19 -> 95; 20 -> 40; else -> 0 }.toLong() * 60_000L
        })
        return JSONObject().put("daily", daily).put("hourly", hourly).put("since", System.currentTimeMillis() - 20L * 86_400_000L)
            .put("byApp", JSONObject().put("com.netflix.ninja", 260 * 60_000L).put("com.google.android.youtube.tv", 210 * 60_000L)
                .put("com.disney.disneyplus", 120 * 60_000L).put("fr.francetv.pluzz", 60 * 60_000L).put("com.spotify.tv.android", 17 * 60_000L))
            .put("labels", labels)
    }

    @Test fun dashboard() {
        val a = Robolectric.buildActivity(BoxDashboardActivity::class.java, intent(BoxDashboardActivity::class.java, BoxDashboardActivity.EXTRA_ID)).setup().get()
        set(a, "status", status(withRequest = true)); set(a, "week", week()); set(a, "error", null)
        call(a, "render"); shot(a, "01-tableau-de-bord")
        set(a, "status", status(withRequest = false)); call(a, "render"); shot(a, "02-tableau-de-bord-sans-demande")
        set(a, "tab", 1); call(a, "render")
        (a.window.decorView.findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0).scrollTo(0, 1500)
        shot(a, "03-semaine")
        set(a, "tab", 2); call(a, "render"); shot(a, "04-horaires")
    }

    @Test fun rules() {
        val a = Robolectric.buildActivity(BoxRulesActivity::class.java, intent(BoxRulesActivity::class.java, BoxRulesActivity.EXTRA_ID)).setup().get()
        ShadowDialog.getLatestDialog()?.dismiss()
        set(a, "unlocked", true); set(a, "syncState", "Synchronisé avec la box")
        set(a, "apps", listOf("com.netflix.ninja" to "Netflix", "com.google.android.youtube.tv" to "YouTube", "com.disney.disneyplus" to "Disney+", "com.twitch.android.app" to "Twitch", "fr.francetv.pluzz" to "france.tv"))
        call(a, "render"); shot(a, "05-regles")
        (a.window.decorView.findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0).scrollTo(0, 2300)
        shot(a, "05b-regles-suite")
    }

    /** Images intermédiaires pour les animations du site (défilement réel des écrans). */
    @Test fun gifFrames() {
        val dir = File(out, "frames").apply { mkdirs() }
        fun save(a: Activity, name: String) {
            val root = a.window.decorView
            val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun scroller(a: Activity) = (a.window.decorView.findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        fun layout(a: Activity) {
            val root = a.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2400)
        }
        val r = Robolectric.buildActivity(BoxRulesActivity::class.java, intent(BoxRulesActivity::class.java, BoxRulesActivity.EXTRA_ID)).setup().get()
        ShadowDialog.getLatestDialog()?.dismiss()
        set(r, "unlocked", true); set(r, "syncState", "Synchronisé avec la box")
        set(r, "apps", listOf("com.netflix.ninja" to "Netflix", "com.google.android.youtube.tv" to "YouTube", "com.disney.disneyplus" to "Disney+", "com.twitch.android.app" to "Twitch", "fr.francetv.pluzz" to "france.tv"))
        call(r, "render"); layout(r)
        for (i in 0..40) { scroller(r).scrollTo(0, i * 60); save(r, "regles-%02d".format(i)) }

        val d = Robolectric.buildActivity(BoxDashboardActivity::class.java, intent(BoxDashboardActivity::class.java, BoxDashboardActivity.EXTRA_ID)).setup().get()
        set(d, "status", status(withRequest = false)); set(d, "week", week()); set(d, "error", null)
        for (t in 0..2) { set(d, "tab", t); call(d, "render"); layout(d); scroller(d).scrollTo(0, if (t == 0) 1624 else 1500); save(d, "activite-$t") }
    }

    @Test fun wizard() {
        val a = Robolectric.buildActivity(RulesWizardActivity::class.java, intent(RulesWizardActivity::class.java, RulesWizardActivity.EXTRA_ID)).setup().get()
        ShadowDialog.getLatestDialog()?.dismiss()
        shot(a, "06-assistant")
    }

    @Test fun pinApproval() {
        val i = Intent(ctx, fr.paul.telecommande.ui.PinApproveActivity::class.java)
            .putExtra("device", device.id).putExtra("id", "p1").putExtra("title", "Continuer la lecture (+30 min)")
        val a = Robolectric.buildActivity(fr.paul.telecommande.ui.PinApproveActivity::class.java, i).setup().get()
        shot(a, "07-validation-code", withDialog = true)
    }
}

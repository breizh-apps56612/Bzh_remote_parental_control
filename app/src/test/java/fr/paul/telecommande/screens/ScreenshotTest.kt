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
@Config(sdk = [34], qualifiers = "w412dp-h915dp-notnight-xxhdpi")
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
        r.profiles = mutableListOf(Profile("leo", "Léo", "🦖", 90, birthYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 7),
            Profile("emma", "Emma", "🦄", 0, birthYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 11), Profile("par", "Parents", "👑", 0, true))
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

    /** Premier ScrollView de l'écran (le contenu est enveloppé par la barre de navigation flottante). */
    private fun scroller(a: Activity): View {
        fun find(v: View): View? = when (v) {
            is android.widget.ScrollView -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.findViewById(android.R.id.content))!!
    }

    /** Vue dont le texte ou la description correspond exactement. */
    private fun view(a: Activity, label: String): View? {
        fun find(v: View): View? {
            if (v.contentDescription?.toString() == label || (v as? android.widget.TextView)?.text?.toString() == label) return v
            return (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        }
        return find(a.window.decorView)
    }

    /** Positions (px, écran 1080×2400) des éléments utiles aux animations du site : nom → [gauche, haut, droite, bas]. */
    private fun bounds(file: String, views: Map<String, View?>) {
        val o = JSONObject()
        views.forEach { (k, v) ->
            if (v == null) return@forEach
            val p = IntArray(2); v.getLocationInWindow(p)
            o.put(k, JSONArray(listOf(p[0], p[1], p[0] + v.width, p[1] + v.height)))
        }
        File(out, "$file.json").writeText(o.toString(1))
    }

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
        if (withDialog) ShadowDialog.getLatestDialog()?.window?.decorView?.let { d ->
            val dw = (w * 0.86).toInt(); val x = (w - dw) / 2; val y = (h - d.measuredHeight) / 2
            File(out, "$file.json").writeText(JSONObject().put("dialog", JSONArray(listOf(x, y, x + dw, y + d.measuredHeight))).toString(1))
        }
    }

    private fun intent(cls: Class<*>, key: String) = Intent(ctx, cls).putExtra(key, device.id)

    private val labels = JSONObject().put("com.netflix.ninja", "Netflix").put("com.google.android.youtube.tv", "YouTube")
        .put("com.disney.disneyplus", "Disney+").put("fr.francetv.pluzz", "france.tv").put("com.spotify.tv.android", "Spotify")

    private fun status(withRequest: Boolean) = JSONObject()
        .put("interactive", true).put("label", "Netflix")
        .put("profile", JSONObject().put("id", "leo").put("emoji", "🦖").put("name", "Léo"))
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
        // Écran « Parental » : demande en attente, profils, règle du profil, actions, applis
        val a = Robolectric.buildActivity(BoxDashboardActivity::class.java, intent(BoxDashboardActivity::class.java, BoxDashboardActivity.EXTRA_ID)
            .putExtra(BoxDashboardActivity.EXTRA_MODE, BoxDashboardActivity.MODE_PARENTAL)).setup().get()
        set(a, "status", status(withRequest = true)); set(a, "week", week()); set(a, "error", null)
        call(a, "render"); shot(a, "01-tableau-de-bord")
        bounds("01-tableau-de-bord", mapOf("accept" to view(a, "Accepter")))
        set(a, "status", status(withRequest = false)); call(a, "render"); shot(a, "02-tableau-de-bord-sans-demande")
        // Écran « Activité » : anneau du jour puis le détail en onglets
        val b = Robolectric.buildActivity(BoxDashboardActivity::class.java, intent(BoxDashboardActivity::class.java, BoxDashboardActivity.EXTRA_ID)
            .putExtra(BoxDashboardActivity.EXTRA_MODE, BoxDashboardActivity.MODE_ACTIVITY)).setup().get()
        set(b, "status", status(withRequest = false)); set(b, "week", week()); set(b, "error", null)
        set(b, "tab", 1); call(b, "render"); shot(b, "03-semaine")
        set(b, "tab", 2); call(b, "render"); shot(b, "04-horaires")
    }

    /** Accueil : carte « en direct » de la TV principale, tuiles, demande en attente, écrans. */
    @Test fun home() {
        val a = Robolectric.buildActivity(fr.paul.telecommande.ui.MainActivity::class.java).setup().get()
        repeat(10) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
        ShadowDialog.getLatestDialog()?.dismiss()
        @Suppress("UNCHECKED_CAST")
        val st = a.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(a) as HashMap<String, JSONObject?>
        st[device.id] = status(withRequest = true)
        call(a, "refresh"); shot(a, "10-accueil")
    }

    @Test fun rules() {
        val a = Robolectric.buildActivity(BoxRulesActivity::class.java, intent(BoxRulesActivity::class.java, BoxRulesActivity.EXTRA_ID)).setup().get()
        ShadowDialog.getLatestDialog()?.dismiss()
        set(a, "unlocked", true); set(a, "syncState", "Synchronisé avec la box")
        set(a, "apps", listOf("com.netflix.ninja" to "Netflix", "com.google.android.youtube.tv" to "YouTube", "com.disney.disneyplus" to "Disney+", "com.twitch.android.app" to "Twitch", "fr.francetv.pluzz" to "france.tv"))
        call(a, "render"); shot(a, "05-regles")
        // Liste des applis : on fait défiler jusqu'à elle et on note où elle se trouve
        val label = view(a, "APPLIS DE LA TV")
        val list = (label?.parent as? android.view.ViewGroup)?.let { g -> g.getChildAt(g.indexOfChild(label) + 1) }
        shot(a, "05-regles")
        scroller(a).scrollTo(0, ((label?.top ?: 2300) - 200).coerceAtLeast(0))
        shot(a, "05b-regles-suite")
        bounds("05b-regles-suite", mapOf("label" to label, "list" to list))
        // Règle d'une appli : Disney+ le week-end, de 14 h à 18 h
        a.javaClass.getDeclaredMethod("editApp", String::class.java, String::class.java).apply { isAccessible = true }
            .invoke(a, "com.disney.disneyplus", "Disney+")
        shot(a, "05c-regle-appli", withDialog = true)
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
        for (i in 0..64) { scroller(r).scrollTo(0, i * 60); save(r, "regles-%02d".format(i)) }

        val d = Robolectric.buildActivity(BoxDashboardActivity::class.java, intent(BoxDashboardActivity::class.java, BoxDashboardActivity.EXTRA_ID)
            .putExtra(BoxDashboardActivity.EXTRA_MODE, BoxDashboardActivity.MODE_ACTIVITY)).setup().get()
        set(d, "status", status(withRequest = false)); set(d, "week", week()); set(d, "error", null)
        for (t in 0..2) {
            set(d, "tab", t); call(d, "render"); layout(d); save(d, "activite-$t")
            if (t == 0) {
                val tabs = view(d, "Aujourd'hui")?.parent as? View
                File(out, "activite.json").writeText(JSONObject().apply {
                    fun put(k: String, v: View?) { if (v != null) { val p = IntArray(2); v.getLocationInWindow(p); put(k, JSONArray(listOf(p[0], p[1], p[0] + v.width, p[1] + v.height))) } }
                    put("tabs", tabs)
                    listOf("Aujourd'hui", "Semaine", "Horaires").forEachIndexed { i, n -> put("tab$i", view(d, n) ?: view(d, "$n, sélectionné")) }
                    put("hero", (view(d, "QUI REGARDE")?.parent?.parent?.parent as? View))
                }.toString(1))
            }
        }
    }

    /** Assistant d'installation de l'appli TV : les étapes se cochent au fil de ce que le téléphone observe sur la TV. */
    @Test fun installFrames() {
        val dir = File(out, "frames").apply { mkdirs() }
        fr.paul.telecommande.core.ApkServer.bundledForTest = true
        val i = Intent(ctx, fr.paul.telecommande.ui.InstallBoxActivity::class.java).putExtra(fr.paul.telecommande.ui.InstallBoxActivity.EXTRA_ID, device.id)
        val a = Robolectric.buildActivity(fr.paul.telecommande.ui.InstallBoxActivity::class.java, i).setup().get()
        val srv = a.javaClass.getDeclaredField("server").apply { isAccessible = true }.get(a) as fr.paul.telecommande.core.ApkServer
        @Suppress("UNCHECKED_CAST")
        val st = srv.javaClass.getDeclaredField("_state").apply { isAccessible = true }.get(srv) as kotlinx.coroutines.flow.MutableStateFlow<fr.paul.telecommande.core.ApkServer.State>
        fun frame(name: String, tvApp: String, state: fr.paul.telecommande.core.ApkServer.State, boxUp: Boolean = false) {
            set(a, "tvConnected", true); set(a, "tvApp", tvApp); set(a, "boxUp", boxUp); set(a, "busy", null); set(a, "note", "")
            st.value = state
            call(a, "render")
            val root = a.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2400)
            val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        frame("install-1", "", fr.paul.telecommande.core.ApkServer.State.READY)
        frame("install-2", "com.android.vending", fr.paul.telecommande.core.ApkServer.State.READY)
        frame("install-3", "com.esaba.downloader", fr.paul.telecommande.core.ApkServer.State.READY)
        frame("install-4", "com.esaba.downloader", fr.paul.telecommande.core.ApkServer.State.DOWNLOADING)
        frame("install-5", "com.esaba.downloader", fr.paul.telecommande.core.ApkServer.State.DONE)
        frame("install-6", "fr.paul.telecommande.box", fr.paul.telecommande.core.ApkServer.State.DONE)
        frame("install-7", "fr.paul.telecommande.box", fr.paul.telecommande.core.ApkServer.State.DONE, boxUp = true)
        set(a, "manualPath", true)
        frame("install-3b", "com.esaba.downloader", fr.paul.telecommande.core.ApkServer.State.READY)
    }

    @Test fun wizard() {
        val a = Robolectric.buildActivity(RulesWizardActivity::class.java, intent(RulesWizardActivity::class.java, RulesWizardActivity.EXTRA_ID)).setup().get()
        ShadowDialog.getLatestDialog()?.dismiss()
        shot(a, "06-assistant")
        // Assistant déverrouillé : deux enfants d'âges éloignés, un couvre-feu qui coupe avant la fin des horaires
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val r = fr.paul.telecommande.shared.BoxRules(enabled = true, bedtimeEnabled = true, bedtimeStart = 20 * 60, activeFrom = 7 * 60, activeTo = 21 * 60,
            profiles = mutableListOf(fr.paul.telecommande.shared.Profile("leo", "Léo", "🦖", 60, birthYear = year - 7),
                fr.paul.telecommande.shared.Profile("emma", "Emma", "🦄", 120, birthYear = year - 13)))
        set(a, "rules", r)
        a.javaClass.getDeclaredField("content").apply { isAccessible = true }.let { (it.get(a) as View).visibility = View.VISIBLE }
        set(a, "index", 0); call(a, "render"); shot(a, "06a-assistant-profils")
        set(a, "index", 2); call(a, "render"); shot(a, "06b-assistant-temps")
        @Suppress("UNCHECKED_CAST") val n = (a.javaClass.getDeclaredField("steps").apply { isAccessible = true }.get(a) as List<Any>).size
        set(a, "index", n - 1); call(a, "render"); shot(a, "06c-assistant-verification")
    }

    @Test fun pinApproval() {
        val i = Intent(ctx, fr.paul.telecommande.ui.PinApproveActivity::class.java)
            .putExtra("device", device.id).putExtra("id", "p1").putExtra("title", "Continuer la lecture (+30 min)")
        val a = Robolectric.buildActivity(fr.paul.telecommande.ui.PinApproveActivity::class.java, i).setup().get()
        shot(a, "07-validation-code", withDialog = true)
    }
}

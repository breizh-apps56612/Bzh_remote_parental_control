package fr.paul.telecommande.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import fr.paul.telecommande.R
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DeviceType
import fr.paul.telecommande.core.DriverHub
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.drivers.Driver
import fr.paul.telecommande.ui.RemoteActivity
import fr.paul.telecommande.ui.TouchpadView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File

/**
 * Teste l'écran Télécommande avec une TV simulée qui enregistre les touches reçues :
 * boutons, volume, et vrais gestes sur le pavé tactile (glisser, toucher).
 * Avec SCREENS=1, enregistre aussi des captures de l'écran.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-night-xxhdpi")
class RemoteTest {
    /** TV simulée : connexion immédiate, chaque touche est notée. */
    class FakeTv(d: Device) : Driver(d) {
        val keys = mutableListOf<RemoteKey>()
        override suspend fun connect(ui: fr.paul.telecommande.drivers.PairingUi) {}
        override suspend fun send(key: RemoteKey): Boolean { keys += key; return true }
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var device: Device
    private lateinit var tv: FakeTv

    @Before fun setUp() {
        device = Device(id = "salon-remote", name = "TV du salon", type = DeviceType.ANDROID_TV, host = "10.0.0.2")
        DeviceStore(ctx).save(device)
        tv = FakeTv(device)
        @Suppress("UNCHECKED_CAST")
        val map = DriverHub::class.java.getDeclaredField("drivers").apply { isAccessible = true }.get(DriverHub) as HashMap<String, Driver>
        map[device.id] = tv
    }

    private fun idle() { repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } }

    private fun launch(): RemoteActivity {
        val a = Robolectric.buildActivity(RemoteActivity::class.java, Intent(ctx, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ID, device.id)).setup().get()
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        idle()
        return a
    }

    private fun swipe(v: View, dx: Float, dy: Float) {
        val t = SystemClock.uptimeMillis()
        val cx = v.width / 2f; val cy = v.height / 2f
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0))
        for (i in 1..6) v.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16L, MotionEvent.ACTION_MOVE, cx + dx * i / 6, cy + dy * i / 6, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, cx + dx, cy + dy, 0))
        idle()
    }

    private fun tapCenter(v: View) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, v.width / 2f, v.height / 2f, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, v.width / 2f, v.height / 2f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)); idle()
    }

    private fun tapAt(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
        idle()
    }

    private fun press(v: View) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, 5f, 5f, 0))
        idle()
    }

    @Test fun buttonsSendTheRightKeys() {
        val a = launch()
        a.findViewById<View>(R.id.btnHome).performClick(); idle()
        a.findViewById<View>(R.id.btnBack).performClick(); idle()
        a.findViewById<View>(R.id.btnPlay).performClick(); idle()
        // Barres du pavé : volume à droite (haut, bas, milieu = muet), chaînes à gauche (haut, bas)
        val pad = a.findViewById<TouchpadView>(R.id.touchpad)
        val edge = 20 * ctx.resources.displayMetrics.density
        tapAt(pad, pad.width - edge, pad.height * 0.2f); tapAt(pad, pad.width - edge, pad.height * 0.8f)
        tapAt(pad, pad.width - edge, pad.height * 0.5f)
        tapAt(pad, edge, pad.height * 0.2f); tapAt(pad, edge, pad.height * 0.8f)
        assertEquals(listOf(RemoteKey.HOME, RemoteKey.BACK, RemoteKey.PLAY_PAUSE,
            RemoteKey.VOL_UP, RemoteKey.VOL_DOWN, RemoteKey.MUTE, RemoteKey.CH_UP, RemoteKey.CH_DOWN), tv.keys)
    }

    @Test fun touchpadGestures() {
        val a = launch()
        val pad = a.findViewById<TouchpadView>(R.id.touchpad)
        assertTrue("pavé tactile trop petit : ${pad.width}×${pad.height}", pad.width > 300 && pad.height > 300)
        val step = 48 * ctx.resources.displayMetrics.density
        // Glissement court (un peu plus d'un cran) : une seule flèche par direction
        val short = step * 1.3f
        swipe(pad, short, 0f); swipe(pad, -short, 0f); swipe(pad, 0f, -short); swipe(pad, 0f, short)
        tapCenter(pad)
        assertEquals(listOf(RemoteKey.RIGHT, RemoteKey.LEFT, RemoteKey.UP, RemoteKey.DOWN, RemoteKey.OK), tv.keys)
        // Glissement long (3,3 crans) : trois flèches, comme sur un pavé tactile
        tv.keys.clear()
        swipe(pad, 0f, step * 3.3f)
        assertEquals(listOf(RemoteKey.DOWN, RemoteKey.DOWN, RemoteKey.DOWN), tv.keys)
    }

    @Test fun swipeThenHoldRepeatsTheArrow() {
        val a = launch()
        val pad = a.findViewById<TouchpadView>(R.id.touchpad)
        val step = 48 * ctx.resources.displayMetrics.density
        val t = SystemClock.uptimeMillis()
        val cx = pad.width / 2f; val cy = pad.height / 2f
        pad.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0))
        for (i in 1..6) pad.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16L, MotionEvent.ACTION_MOVE, cx - step * 1.3f * i / 6, cy, 0))
        // Le doigt reste posé : la flèche gauche devient « maintenue » et se répète (retour en continu)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1200)); idle()
        pad.dispatchTouchEvent(MotionEvent.obtain(t, t + 1300, MotionEvent.ACTION_UP, cx - step * 1.3f, cy, 0))
        idle()
        val n = tv.keys.size
        assertTrue("flèche maintenue non répétée : ${tv.keys}", n >= 3 && tv.keys.all { it == RemoteKey.LEFT })
        // Doigt levé : plus rien n'est envoyé
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800)); idle()
        assertEquals(n, tv.keys.size)
    }

    @Test fun screenshots() {
        org.junit.Assume.assumeTrue(System.getenv("SCREENS") == "1")
        val out = File(System.getenv("SCREENS_DIR") ?: "build/screens").apply { mkdirs() }
        val a = launch()
        // Panneau contextuel « en cours » : appli et programme affichés comme avec une vraie box
        val snap = Class.forName("fr.paul.telecommande.ui.RemoteActivity\$Snapshot").declaredConstructors.first { it.parameterCount == 5 }.apply { isAccessible = true }
        fun setF(n: String, v: Any?) = RemoteActivity::class.java.getDeclaredField(n).apply { isAccessible = true }.set(a, v)
        setF("boxMedia", JSONObject().put("title", "Les Explorateurs du ciel").put("subtitle", "Saison 2 · Épisode 4").put("pkg", "com.netflix.ninja"))
        setF("panelSignature", "")
        val s = snap.newInstance("com.netflix.ninja", fr.paul.telecommande.youtube.YtState(), null, true, null)
        RemoteActivity::class.java.getDeclaredMethod("render", s.javaClass).apply { isAccessible = true }.invoke(a, s)
        idle()
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "00-telecommande.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

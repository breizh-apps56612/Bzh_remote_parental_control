package fr.paul.telecommande.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import android.content.Intent
import android.speech.RecognizerIntent
import android.content.res.ColorStateList
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.os.SystemClock
import fr.paul.telecommande.drivers.VoiceRecorder
import kotlinx.coroutines.Job
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import fr.paul.telecommande.R
import fr.paul.telecommande.core.AppCatalog
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceType
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DriverHub
import fr.paul.telecommande.core.Discovery
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.core.TvMonitor
import fr.paul.telecommande.core.Usage
import fr.paul.telecommande.core.RemoteException
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.drivers.AndroidTvDriver
import fr.paul.telecommande.drivers.Driver
import fr.paul.telecommande.drivers.PairingUi
import fr.paul.telecommande.service.AssistantService
import fr.paul.telecommande.youtube.DialScreenId
import fr.paul.telecommande.youtube.YtMeta
import fr.paul.telecommande.youtube.YouTubeAssistant
import fr.paul.telecommande.youtube.YtState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume

class RemoteActivity : AppCompatActivity(), PairingUi {
    companion object {
        const val EXTRA_ID = "device_id"
        const val HINT = "Glisser · naviguer    Toucher · OK\nRester appuyé à gauche / droite · en continu"
        /** Ouvre directement le menu Administrateur (depuis Réglages). */
        const val EXTRA_ADMIN = "admin"
    }

    private lateinit var store: DeviceStore
    private lateinit var device: Device
    private lateinit var driver: Driver
    private var atv: AndroidTvDriver? = null
    private var assistant: YouTubeAssistant? = null

    private lateinit var status: TextView
    private lateinit var panel: LinearLayout
    private lateinit var touchpad: TouchpadView
    private lateinit var stateDot: View

    private val mutex = Mutex()
    private var connected = false
    private val connectedFlow = MutableStateFlow(false)
    private var currentKind = AppCatalog.Kind.HOME
    private var panelSignature = ""
    private var ytTime: TextView? = null
    private var ytAuthor = ""
    private var ytBar: SegmentBar? = null
    private val d by lazy { resources.displayMetrics.density }
    private var recorder: VoiceRecorder? = null
    private var voiceJob: Job? = null
    private var micDownAt = 0L

    /** Repli si la box n'accepte pas la voix : reconnaissance vocale du téléphone, puis saisie du texte. */
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
        val tv = atv ?: return@registerForActivityResult
        lifecycleScope.launch {
            // Avec l'appli Box : vraie recherche texte (la touche Recherche ouvre l'Assistant vocal sur certaines box)
            if (device.hasBoxApp) {
                val r = runCatching { fr.paul.telecommande.core.BoxClient.of(this@RemoteActivity, device).call("search", org.json.JSONObject().put("q", text)) }.getOrNull()
                if (r?.optBoolean("ok") == true) { toast("« $text » recherché sur la TV"); fr.paul.telecommande.core.DiagLog.log("VOIX", "Reconnu par le téléphone : « $text »"); return@launch }
            }
            mutex.withLock {
                if (!ensureConnected()) return@withLock
                runCatching {
                    tv.sendKeyCode(84)          // ouvre la recherche de la box
                    delay(1500)
                    tv.sendText(text); tv.send(RemoteKey.OK)
                }
                    .onSuccess { toast("« $text » envoyé") }
                    .onFailure { showStatus("Erreur : ${it.message}") }
            }
        }
    }

    /** Dernier appui sur le retour du téléphone (le second, juste après, ferme la télécommande). */
    private var backAt = 0L

    /** La télécommande est toujours sombre, comme une vraie : on ne la confond pas avec les écrans (clairs) de l'appli. */
    override fun attachBaseContext(newBase: android.content.Context) {
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        // Retour du téléphone : on prévient d'abord (c'est souvent la touche Retour de la TV qu'on voulait),
        // un second appui dans les 2,5 s ferme la télécommande
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - backAt < 2_500) { closeRemote(); return }
                backAt = now
                window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                Toast.makeText(this@RemoteActivity, "Pour la TV : touche « Retour TV » en bas.\nEncore une fois pour fermer la télécommande.", Toast.LENGTH_SHORT).show()
            }
        })
        store = DeviceStore(this)
        val dev = intent.getStringExtra(EXTRA_ID)?.let { store.get(it) }
        if (dev == null) { finish(); return }
        device = dev
        driver = DriverHub.driver(this, device)
        atv = driver as? AndroidTvDriver
        if (device.isSmartBox) assistant = DriverHub.assistant(this, device)

        status = findViewById(R.id.status)
        panel = findViewById(R.id.contextPanel)
        touchpad = findViewById(R.id.touchpad)
        stateDot = findViewById(R.id.stateDot)
        findViewById<TextView>(R.id.deviceName).text = device.name
        setDot(false)

        Ui.systemBars(this)
        findViewById<View>(R.id.deviceChip).setOnClickListener { showDeviceSwitcher() }
        findViewById<View>(R.id.btnPower).apply {
            setOnClickListener { atv?.allowPowerOnce(); press(RemoteKey.POWER) }
            setOnLongClickListener { atv?.allowPowerOnce(); powerOn(); true }
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMoreKeys() }
        findViewById<View>(R.id.btnClose).setOnClickListener { closeRemote() }
        AuroraNav.setPrimary(this, device)
        if (intent.getBooleanExtra(EXTRA_ADMIN, false)) window.decorView.post { showAdmin() }
        findViewById<View>(R.id.btnApps).apply {
            if (device.isSmartBox) setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); showAppsSheet() }
            else {
                // Pas d'applis à lancer sur une TV classique : la touche devient « Source »
                findViewById<TextView>(R.id.btnAppsLabel).text = "Source"
                contentDescription = "Source (entrée HDMI, TV…)"
                setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); press(RemoteKey.INPUT) }
            }
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { press(RemoteKey.BACK) }
        findViewById<View>(R.id.btnHome).setOnClickListener { press(RemoteKey.HOME) }
        findViewById<View>(R.id.btnPlay).setOnClickListener { press(RemoteKey.PLAY_PAUSE) }
        touchpad.holdListener = { g, down -> holdArrow(g, down) }
        setupNav()
        setupMic()
        touchpad.listener = ::onGesture
        touchpad.stepListener = { g -> MouseHold.step(lifecycleScope, driver, g) }
        touchpad.releaseListener = { MouseHold.release() }
        // Souris Bluetooth connectée : le pavé déplace une vraie flèche sur la TV
        lifecycleScope.launch { fr.paul.telecommande.core.BtMouse.state.collect { routeMouse() } }
        if (fr.paul.telecommande.core.BtMouse.savedHost(this) != null && btGranted()) fr.paul.telecommande.core.BtMouse.start(this)
        if (!device.isSmartBox) touchpad.hint = HINT

        lifecycleScope.launch { mutex.withLock { ensureConnected() } }
        // Appli Box : une zone de texte est sélectionnée sur la TV → le clavier du téléphone s'ouvre
        if (device.hasBoxApp) lifecycleScope.launch {
            fr.paul.telecommande.core.BoxLink.events.collect { (id, ev) ->
                if (id == device.id && ev.optString("event") == "field" && keyboard?.isShowing != true) showKeyboard()
            }
        }
        // Bascule automatique en mode clavier dès qu'un champ de saisie s'ouvre sur la TV
        atv?.let { tv ->
            lifecycleScope.launch {
                var baseline = -1
                tv.imeShown.collect { n ->
                    if (baseline < 0) { baseline = n; return@collect }
                    if (n > baseline) { baseline = n; showKeyboard() }
                }
            }
            lifecycleScope.launch {
                var last = tv.currentApp.value
                tv.currentApp.collect { app -> if (app != last) { last = app; keyboard?.close() } }
            }
        }
        assistant?.let { a ->
            if (device.ytScreenId.isNotBlank() && (device.ytAutoSkipAds || device.ytSkipSponsors)) {
                a.start(); AssistantService.start(this)
            }
        }
        observe()
    }

    /** Referme le volet de la télécommande et revient à l'appli. */
    private fun closeRemote() {
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ---------- État et panneau contextuel ----------

    private fun observe() {
        val appFlow = atv?.currentApp ?: flowOf("")
        val ytFlow = assistant?.state ?: flowOf(YtState())
        lifecycleScope.launch {
            combine(appFlow, ytFlow, DriverHub.sleepAt, connectedFlow, errorFlow) { app, yt, sleep, conn, err ->
                Snapshot(app, yt, sleep[device.id], conn, err)
            }.collect { render(it) }
        }
        // Appli Box : titre et image de ce qui est regardé (toutes applis qui le publient)
        if (device.hasBoxApp) lifecycleScope.launch {
            while (isActive) {
                val m = runCatching { fr.paul.telecommande.core.BoxClient.of(this@RemoteActivity, device).call("status").optJSONObject("media") }.getOrNull()
                val key = m?.optString("title") + "|" + m?.optString("subtitle")
                if (key != boxMediaKey) {
                    boxMediaKey = key
                    boxMedia = m
                    boxArt = m?.optString("art")?.takeIf { it.isNotBlank() }?.let { b ->
                        runCatching { android.util.Base64.decode(b, android.util.Base64.NO_WRAP) }.getOrNull()
                            ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                    panelSignature = ""; lastSnapshot?.let { render(it) }
                }
                delay(5_000)
            }
        }
        // Temps d'écran du jour : rafraîchi chaque minute
        lifecycleScope.launch {
            while (isActive) { delay(60_000); lastSnapshot?.let { panelSignature = ""; render(it) } }
        }
        // Avancement de la barre YouTube entre deux événements
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val a = assistant ?: continue
                val s = a.state.value
                ytBar?.let { it.duration = s.duration; it.position = a.position() }
                ytTime?.text = ytAuthor + timeLabel(a.position(), s.duration)
            }
        }
    }

    private var lastSnapshot: Snapshot? = null
    private var boxMedia: org.json.JSONObject? = null
    private var boxMediaKey: String? = null
    private var boxArt: android.graphics.Bitmap? = null

    /** Ce que l'appli Box sait de la lecture en cours dans [pkg] (titre, sous-titre, image). */
    private fun boxMediaFor(pkg: String): org.json.JSONObject? = boxMedia?.takeIf { it.optString("pkg") == pkg || pkg.isBlank() }

    private data class Snapshot(val app: String, val yt: YtState, val sleepAt: Long?, val connected: Boolean, val error: String? = null)

    private fun render(s: Snapshot) {
        lastSnapshot = s
        // Applis à curseur (Downloader, navigateurs) : mode souris activé automatiquement
        val cursorApp = s.app in MouseHold.CURSOR_APPS
        if (cursorApp && autoMouseFor != s.app) { autoMouseFor = s.app; touchpad.mouseMode = true }
        if (!cursorApp && autoMouseFor != null) { autoMouseFor = null; touchpad.mouseMode = false }
        setDot(s.connected)
        currentKind = if (device.isSmartBox) AppCatalog.kind(s.app) else AppCatalog.Kind.OTHER
        touchpad.hint = when {
            !device.isSmartBox -> HINT
            currentKind == AppCatalog.Kind.LIVE_TV -> HINT
            else -> HINT
        }
        val y = s.yt
        val sig = listOf(touchpad.mouseMode, s.yt.videoId, s.error, device.isSmartBox, currentKind, s.app, s.connected, s.sleepAt, y.linked, y.connected, y.adPlaying,
            y.adsSkipped, y.sponsorsSkipped, y.speed, y.message, y.segments.size, device.ytAutoSkipAds, device.ytSkipSponsors).joinToString("|")
        if (sig == panelSignature) return
        panelSignature = sig
        panel.removeAllViews()
        ytTime = null; ytBar = null
        if (!s.connected && s.error != null) { renderOffline(s.error); return }
        if (!device.isSmartBox) { renderBasic(); return }
        when (currentKind) {
            AppCatalog.Kind.YOUTUBE -> renderYoutube(s)
            AppCatalog.Kind.LIVE_TV -> renderLive(s)
            AppCatalog.Kind.HOME -> renderHome(s)
            else -> renderApp(s)
        }
        if (s.app in MouseHold.CURSOR_APPS) {
            chips(
                chip(if (touchpad.mouseMode) "✓ Mode souris" else "Mode souris", on = touchpad.mouseMode) { setMouse(!touchpad.mouseMode) },
                chip("Vraie souris") { startActivity(Intent(this, BtMouseActivity::class.java)) },
                chip("Zoom −") { press(RemoteKey.REWIND) }, chip("Zoom +") { press(RemoteKey.FAST_FORWARD) },
                chip("Menu") { press(RemoteKey.MENU) }
            )
        }
    }

    private var autoMouseFor: String? = null

    /** Connexion directe à l'appli Box (souris réelle, saisie, applis) si elle est installée et reliée. */
    private val box: fr.paul.telecommande.core.BoxSession? by lazy {
        if (device.hasBoxApp) fr.paul.telecommande.core.BoxSession.of(this, device).also { b ->
            b.onError = { msg -> runOnUiThread { toast(msg) } }
        } else null
    }

    /** Qui pilote la flèche : souris Bluetooth > appli Box > flèches. */
    private fun routeMouse() {
        val bt = fr.paul.telecommande.core.BtMouse.state.value == fr.paul.telecommande.core.BtMouse.State.CONNECTED
        val b = box
        when {
            bt -> {
                touchpad.rawMoveListener = { dx, dy -> fr.paul.telecommande.core.BtMouse.move(dx, dy) }
                touchpad.scrollListener = { n -> fr.paul.telecommande.core.BtMouse.scroll(-n) }
            }
            b != null -> {
                touchpad.rawMoveListener = { dx, dy -> b.move(dx * 2.4f, dy * 2.4f) }
                touchpad.scrollListener = { n -> b.scroll(n * 160f) }
            }
            else -> { touchpad.rawMoveListener = null; touchpad.scrollListener = null }
        }
    }

    private fun pointerActive() = touchpad.mouseMode &&
        (fr.paul.telecommande.core.BtMouse.state.value == fr.paul.telecommande.core.BtMouse.State.CONNECTED || box != null)

    private fun btGranted() = Build.VERSION.SDK_INT < 31 ||
        androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun setMouse(on: Boolean) {
        if (touchpad.mouseMode == on) return
        touchpad.mouseMode = on
        panelSignature = ""
        lastSnapshot?.let { render(it) }
    }

    private fun renderOffline(error: String) {
        header("Appareil injoignable", device.host.ifBlank { device.type.label.substringBefore(" (") })
        panel.addView(TextView(this).apply {
            text = error; textSize = 13f; setTextColor(color(R.color.muted)); setPadding(0, dp(8), 0, 0)
        })
        chips(
            chip("Réessayer", on = true) {
                errorFlow.value = null
                lifecycleScope.launch { mutex.withLock { rediscovered = false; ensureConnected() } }
            },
            chip("Diagnostic") { showDiagnostic() },
            chip("Modifier l'adresse") { editHost() }
        )
    }

    private fun editHost() {
        val input = EditText(this).apply { setText(device.host); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val box = FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Adresse IP de l'appareil").setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val h = input.text.toString().trim()
                if (h.isNotBlank()) {
                    applyHost(h); connected = false; rediscovered = true; errorFlow.value = null
                    lifecycleScope.launch { mutex.withLock { ensureConnected() } }
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun renderBasic() {
        header("Touches rapides", device.type.label.substringBefore(" ("))
        chips(
            chip("CH −") { press(RemoteKey.CH_DOWN) }, chip("CH +") { press(RemoteKey.CH_UP) },
            chip("Source") { press(RemoteKey.INPUT) }, chip("Infos") { press(RemoteKey.INFO) },
            chip("123 · couleurs") { showMoreKeys() }
        )
        if (device.type == DeviceType.VESTEL) chips(
            chip("Netflix") { openVestelApp("Netflix") }, chip("YouTube") { openVestelApp("YouTube") },
            chip("Accueil") { press(RemoteKey.HOME) }, chip("Menu") { press(RemoteKey.MENU) }
        )
    }

    /** TV Vestel : Netflix et YouTube s'ouvrent par le protocole DIAL, sans passer par les menus. */
    private fun openVestelApp(app: String) {
        val tv = driver as? fr.paul.telecommande.drivers.VestelDriver ?: return
        lifecycleScope.launch {
            if (tv.launchApp(app)) toast("Ouverture de $app…")
            else toast("La TV n'a pas ouvert $app (voir le journal de diagnostic)")
        }
    }

    private fun renderHome(s: Snapshot) {
        header(if (s.connected) "Rien en lecture" else "Connexion à la box…", if (s.connected) "Accueil de ${device.name}" else device.host, if (s.connected) s.app.ifBlank { "home" } else null)
        panel.addView(appsRow())
        val rules = ParentalStore(this).rules(device.id)
        val tracking = device.monitorEnabled || rules.enabled
        val todayTotal = if (tracking) TvMonitor.todayUsage(this, device.id).values.sum() else 0L
        chips(
            *(if (device.hasBoxApp) arrayOf(chip("▶ Reprendre…") { showContinue() }) else emptyArray()),
            sleepChip(s.sleepAt),
            chip(if (tracking) "Temps d'écran · " + Usage.format(todayTotal) else "Activer le temps d'écran") {
                startActivity(Intent(this, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_ID, device.id))
            }
        )
    }

    private fun renderLive(s: Snapshot) {
        val bm = boxMediaFor(s.app)
        if (bm != null) header(bm.optString("title"), listOf(bm.optString("subtitle"), AppCatalog.name(s.app)).filter { it.isNotBlank() }.joinToString(" · "), s.app, art = boxArt)
        else header("TV en direct", AppCatalog.name(s.app), s.app)
        usageLine(s.app)
        chips(
            chip("CH −") { press(RemoteKey.CH_DOWN) }, chip("CH +") { press(RemoteKey.CH_UP) },
            chip("↺ Chaîne d'avant") { press(RemoteKey.LAST_CHANNEL) }, chip("Guide") { press(RemoteKey.GUIDE) },
            chip("123") { showMoreKeys() }, sleepChip(s.sleepAt)
        )
    }

    private fun renderApp(s: Snapshot) {
        val bm = boxMediaFor(s.app)
        if (bm != null) header(bm.optString("title"), listOf(bm.optString("subtitle"), AppCatalog.name(s.app)).filter { it.isNotBlank() }.joinToString(" · "), s.app, art = boxArt)
        else header(AppCatalog.name(s.app), "En cours sur ${device.name}", s.app)
        usageLine(s.app)
        chips(
            chip("−10 s") { press(RemoteKey.REWIND) }, chip("+10 s") { press(RemoteKey.FAST_FORWARD) },
            chip("Rechercher…") { showKeyboard() },
            *(if (device.hasBoxApp) arrayOf(chip("▶ Reprendre…") { showContinue() }) else emptyArray()),
            sleepChip(s.sleepAt)
        )
    }

    private fun renderYoutube(s: Snapshot) {
        val y = s.yt
        val sub = when {
            !y.linked -> "Associez YouTube pour activer l'assistant"
            !(device.ytAutoSkipAds || device.ytSkipSponsors) -> "Assistant en pause"
            !y.connected -> "Connexion à YouTube…"
            y.adPlaying -> if (device.ytAutoSkipAds) "Publicité : passage dès que YouTube l'autorise" else "Publicité en cours"
            else -> timeLabel(assistant?.position() ?: y.position, y.duration)
        }
        val vid = y.videoId.takeIf { it.isNotBlank() && y.linked }
        val meta = vid?.let { YtMeta.cached(it) }
        if (vid != null && meta == null) lifecycleScope.launch {
            if (YtMeta.info(vid) != null) { panelSignature = ""; lastSnapshot?.let { render(it) } }
        }
        val subView = header(meta?.title ?: AppCatalog.name(s.app),
            if (meta != null && !y.adPlaying && y.connected) meta.author + " · " + sub else sub, s.app, vid)
        if (meta != null && !y.adPlaying && y.connected) ytAuthor = meta.author + " · " else ytAuthor = "" 
        usageLine(s.app)
        if (!y.linked) {
            panel.addView(TextView(this).apply {
                text = "L'association permet de passer les pubs automatiquement, sauter les sponsors et avancer de 10 s."
                textSize = 13f; setTextColor(color(R.color.muted)); setPadding(0, dp(10), 0, 0)
            })
            chips(chip("Associer YouTube", on = true) { linkYoutube() }, chip("Rechercher…") { showKeyboard() })
            return
        }
        if (y.connected && !y.adPlaying) {
            ytTime = subView
            val bar = SegmentBar(this).apply { duration = y.duration; segments = y.segments; position = assistant?.position() ?: y.position }
            ytBar = bar
            panel.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(10) })
        }
        chips(
            chip(if (device.ytAutoSkipAds) "✓ Pub : passer auto" else "Pub : passer auto", on = device.ytAutoSkipAds,
                desc = "Passage automatique des pubs, " + if (device.ytAutoSkipAds) "activé" else "désactivé") {
                device.ytAutoSkipAds = !device.ytAutoSkipAds; settingsChanged()
            },
            chip("Sponsors" + (if (device.ytSkipSponsors) " · ${y.sponsorsSkipped}" else " : off"), on = false,
                desc = "Sauter les passages sponsorisés, " + if (device.ytSkipSponsors) "activé" else "désactivé") {
                device.ytSkipSponsors = !device.ytSkipSponsors; settingsChanged()
            },
            chip("−10 s") { assistant?.seekBy(-10.0) }, chip("+10 s") { assistant?.seekBy(10.0) },
            chip("Vitesse " + speedLabel(y.speed)) { assistant?.cycleSpeed() },
            chip("Rechercher…") { showKeyboard() }
        )
        val info = buildList {
            if (y.adsSkipped > 0) add("${y.adsSkipped} pub(s) passée(s)")
            if (y.message.isNotBlank()) add(y.message)
        }.joinToString(" · ")
        if (info.isNotBlank()) panel.addView(TextView(this).apply {
            text = info; textSize = 12f; setTextColor(color(R.color.muted)); setPadding(0, dp(8), 0, 0)
        })
    }

    /** Temps passé aujourd'hui sur l'appli en cours (et limite parentale éventuelle). */
    private fun usageLine(pkg: String) {
        if (!device.isSmartBox || pkg.isBlank()) return
        val rules = ParentalStore(this).rules(device.id)
        if (!device.monitorEnabled && !rules.enabled) return
        val used = TvMonitor.todayUsage(this, device.id)[pkg] ?: 0L
        val limit = if (rules.enabled) rules.perAppMin[pkg] ?: 0 else 0
        val txt = "Aujourd'hui : " + Usage.format(used) + if (limit > 0) " · limite " + Usage.format(limit * 60_000L) else ""
        panel.addView(TextView(this).apply {
            text = txt; textSize = 12f; setPadding(0, dp(8), 0, 0)
            setTextColor(color(if (limit > 0 && used >= limit * 60_000L) R.color.danger else R.color.muted))
        })
    }

    private fun settingsChanged() {
        store.save(device)
        assistant?.settingsChanged()
        panelSignature = ""
        assistant?.let { a ->
            if (device.ytAutoSkipAds || device.ytSkipSponsors) { a.start(); askNotificationPermission(); AssistantService.start(this) }
            else a.stop()
        }
        render(Snapshot(atv?.currentApp?.value ?: "", assistant?.state?.value ?: YtState(), DriverHub.sleepAt.value[device.id], connected, errorFlow.value))
    }

    // ---------- Petits constructeurs de vues ----------

    private fun dp(v: Int) = (v * d).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun letterTile(letter: String, bg: Int, fg: Int, size: Int) = TextView(this).apply {
        text = letter; gravity = Gravity.CENTER; textSize = 13f; setTextColor(fg); setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = GradientDrawable().apply { setColor(bg); cornerRadius = 14 * d }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Carte « En cours » : vignette (miniature YouTube ou pastille de l'appli), titre, sous-titre.
     * Renvoie la vue du sous-titre (mise à jour du temps YouTube).
     */
    private fun header(title: String, subtitle: String, pkg: String? = null, videoId: String? = null, art: android.graphics.Bitmap? = null): TextView {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (art != null) {
            row.addView(android.widget.ImageView(this).apply {
                setImageBitmap(art); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(color(R.color.surface2)); cornerRadius = 14 * d }
                clipToOutline = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(112), dp(63)).apply { marginEnd = dp(12) })
        } else if (videoId != null) {
            val img = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(color(R.color.surface2)); cornerRadius = 14 * d }
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val cached = YtMeta.cachedThumb(videoId)
            if (cached != null) img.setImageBitmap(cached)
            else lifecycleScope.launch { YtMeta.thumbnail(videoId)?.let { img.setImageBitmap(it) } }
            row.addView(img, LinearLayout.LayoutParams(dp(112), dp(63)).apply { marginEnd = dp(12) })
        } else if (pkg != null && device.hasBoxApp && fr.paul.telecommande.core.AppIcons.icon(this, device.id, pkg) != null) {
            val bmp = fr.paul.telecommande.core.AppIcons.icon(this, device.id, pkg)!!
            val wide = bmp.width > bmp.height * 1.3
            row.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bmp); scaleType = if (wide) android.widget.ImageView.ScaleType.CENTER_CROP else android.widget.ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply { setColor(color(R.color.surface2)); cornerRadius = 14 * d }
                clipToOutline = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (!wide) setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(if (wide) 96 else 56), dp(56)).apply { marginEnd = dp(12) })
        } else if (pkg != null) {
            val (bg, letter) = AppCatalog.brand(pkg)
            row.addView(letterTile(letter, bg, Color.WHITE, 52).apply {
                textSize = 17f
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
            })
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title; textSize = 16f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(color(R.color.ink)); typeface = Ui.bold; letterSpacing = -0.01f
        })
        val sub = TextView(this).apply { text = subtitle; textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(color(R.color.muted)) }
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)
        return sub
    }

    private fun chip(label: String, on: Boolean = false, desc: String? = null, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; typeface = Ui.medium
        setTextColor(if (on) Color.WHITE else color(R.color.ink))
        setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        setPadding(dp(14), 0, dp(14), 0); minHeight = dp(40); height = dp(40)
        isClickable = true; isFocusable = true
        contentDescription = desc ?: label
        setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); action() }
    }

    private fun chips(vararg views: View) {
        val group = ChipGroup(this).apply { chipSpacingHorizontal = dp(8); isSingleLine = true }
        views.forEach { group.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))) }
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false
            // Fondu sur le bord : on voit qu'il y a d'autres raccourcis à faire défiler
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(40))
            addView(group)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun sleepChip(at: Long?): View =
        if (at == null) chip("Veille dans 30 min") {
            DriverHub.startSleepTimer(applicationContext, device, 30); AssistantService.start(this)
            toast("La TV se mettra en veille dans 30 minutes")
        } else chip("Veille à " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)) + "  ✕", on = true,
            desc = "Annuler la mise en veille programmée") { DriverHub.cancelSleepTimer(device.id) }

    private fun setDot(ok: Boolean) {
        stateDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ok) color(R.color.ok) else color(R.color.muted)) }
    }

    private fun timeLabel(pos: Double, dur: Double): String {
        fun f(t: Double): String { val s = t.toInt().coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
        return if (dur > 0) "${f(pos)} / ${f(dur)}" else f(pos)
    }

    private fun speedLabel(s: Double) = (if (s % 1.0 == 0.0) "%.0f".format(s) else "%.2f".format(s).trimEnd('0')).replace('.', ',') + "×"

    // ---------- Gestes et touches ----------

    /**
     * Flèche maintenue sur le pavé (glisser puis rester appuyé), comme une touche tenue sur la télécommande Free :
     * dans un lecteur, gauche / droite font un retour / une avance rapide en continu.
     * La touche est répétée tant que le doigt reste posé, de plus en plus vite (comme une touche tenue) :
     * un « appui long » Android TV envoyé à distance n'est pas répété par la box, le lecteur ne voyait qu'un clic.
     */
    private var holdJob: kotlinx.coroutines.Job? = null
    private var heldArrow: RemoteKey? = null

    private fun holdArrow(g: TouchpadView.Gesture, down: Boolean) {
        val key = when (g) {
            TouchpadView.Gesture.LEFT -> RemoteKey.LEFT; TouchpadView.Gesture.RIGHT -> RemoteKey.RIGHT
            TouchpadView.Gesture.UP -> RemoteKey.UP; TouchpadView.Gesture.DOWN -> RemoteKey.DOWN
            else -> return
        }
        holdJob?.cancel(); holdJob = null
        if (down) {
            if (offline) { lifecycleScope.launch { offlineNotice() }; return }
            heldArrow = key
            fr.paul.telecommande.core.DiagLog.log("TOUCHE", "Maintenue : $key")
            holdJob = lifecycleScope.launch {
                val start = android.os.SystemClock.uptimeMillis()
                while (isActive && !offline) {
                    sendKey(key)
                    val held = android.os.SystemClock.uptimeMillis() - start
                    // ~4 clics/s au début, puis ~8/s après 1,5 s, ~12/s après 4 s
                    delay(when { held < 1_500 -> 250L; held < 4_000 -> 125L; else -> 80L })
                }
            }
        } else {
            heldArrow = null
        }
    }

    private fun onGesture(g: TouchpadView.Gesture) {
        when (g) {
            TouchpadView.Gesture.UP -> press(RemoteKey.UP)
            TouchpadView.Gesture.DOWN -> press(RemoteKey.DOWN)
            TouchpadView.Gesture.LEFT -> press(RemoteKey.LEFT)
            TouchpadView.Gesture.RIGHT -> press(RemoteKey.RIGHT)
            TouchpadView.Gesture.TAP -> when {
                touchpad.mouseMode && fr.paul.telecommande.core.BtMouse.state.value == fr.paul.telecommande.core.BtMouse.State.CONNECTED -> fr.paul.telecommande.core.BtMouse.click(1)
                touchpad.mouseMode && box != null -> box?.click()
                else -> press(RemoteKey.OK)
            }
            TouchpadView.Gesture.LONG_PRESS -> press(RemoteKey.MENU)
            TouchpadView.Gesture.VOL_UP -> press(RemoteKey.VOL_UP)
            TouchpadView.Gesture.VOL_DOWN -> press(RemoteKey.VOL_DOWN)
            TouchpadView.Gesture.MUTE -> press(RemoteKey.MUTE)
            TouchpadView.Gesture.CH_UP -> press(RemoteKey.CH_UP)
            TouchpadView.Gesture.CH_DOWN -> press(RemoteKey.CH_DOWN)
            TouchpadView.Gesture.SEEK_BACK, TouchpadView.Gesture.SEEK_FORWARD -> {
                val fwd = g == TouchpadView.Gesture.SEEK_FORWARD
                val a = assistant
                when {
                    currentKind == AppCatalog.Kind.LIVE_TV -> press(if (fwd) RemoteKey.CH_UP else RemoteKey.CH_DOWN)
                    currentKind == AppCatalog.Kind.YOUTUBE && a != null && a.state.value.connected -> a.seekBy(if (fwd) 10.0 else -10.0)
                    device.isSmartBox -> press(if (fwd) RemoteKey.FAST_FORWARD else RemoteKey.REWIND)
                    else -> press(if (fwd) RemoteKey.RIGHT else RemoteKey.LEFT)
                }
            }
        }
    }

    private fun press(key: RemoteKey) { lifecycleScope.launch { sendKey(key) } }

    private suspend fun ensureConnected(): Boolean {
        if (connected) return true
        showStatus("Connexion à ${device.name}…")
        return try {
            driver.connect(this)
            connected = true; connectedFlow.value = true; errorFlow.value = null
            showStatus(null)
            true
        } catch (e: Exception) {
            driver.close()
            // Box Android TV : son adresse a peut-être changé (DHCP) → on la recherche sur le réseau, une fois.
            if (device.isSmartBox && !rediscovered) {
                rediscovered = true
                showStatus("Box introuvable à ${device.host}, recherche sur le réseau…")
                val newHost = findBoxOnNetwork()
                if (newHost != null && newHost != device.host) {
                    applyHost(newHost)
                    return ensureConnected()
                }
            }
            connectedFlow.value = false
            unreachableErr = isUnreachable(e)
            errorFlow.value = e.message ?: e.javaClass.simpleName
            showStatus(null)
            false
        }
    }

    private var rediscovered = false
    private val errorFlow = MutableStateFlow<String?>(null)

    /** Box Android TV visible sur le réseau, correspondant au nom de l'appareil (ou la seule présente). */
    private suspend fun findBoxOnNetwork(): String? {
        val boxes = Discovery(this).androidTvs()
        val key = device.name.lowercase().take(12)
        return (boxes.firstOrNull { it.name.lowercase().startsWith(key) || key.startsWith(it.name.lowercase().take(12)) }
            ?: boxes.singleOrNull())?.host
    }

    private fun applyHost(host: String) {
        device.host = host
        driver.device.host = host
        store.save(device)
        toast("Nouvelle adresse de la box : $host")
    }

    private fun showDiagnostic() {
        val dialog = AlertDialog.Builder(this).setTitle("Diagnostic de connexion").setMessage("Test en cours…")
            .setPositiveButton("Utiliser", null).setNegativeButton("Fermer", null).show()
        val useBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        useBtn.visibility = View.GONE
        lifecycleScope.launch {
            val lines = mutableListOf("Adresse enregistrée : ${device.host.ifBlank { "(aucune)" }}")
            for (port in listOf(6466, 6467)) {
                val t0 = SystemClock.elapsedRealtime()
                val res = withContext(Dispatchers.IO) {
                    runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(device.host, port), 3000) } }
                }
                val dt = SystemClock.elapsedRealtime() - t0
                lines += "Port $port : " + res.fold({ "ouvert ($dt ms)" }, { e ->
                    when (e) { is java.net.ConnectException -> "refusé"; is java.net.SocketTimeoutException -> "pas de réponse"; else -> e.javaClass.simpleName }
                })
            }
            val boxes = Discovery(this@RemoteActivity).androidTvs()
            lines += if (boxes.isEmpty()) "Aucune box Android TV détectée sur le Wi-Fi."
            else "Box Android TV détectées : " + boxes.joinToString { "${it.name.substringBefore(" (")} (${it.host})" }
            lines += ""
            lines += "À vérifier :"
            lines += "• Le téléphone est sur le même Wi-Fi que la box (pas en 4G/5G, pas sur un Wi-Fi invité)."
            lines += "• L'adresse est celle du Player et non de la box Internet : sur la TV, Paramètres > Réseau et Internet > votre réseau. (Chez Free, 192.168.1.254 est le Freebox Server, pas le Player.)"
            lines += "• La box est allumée (pas en veille profonde)."
            dialog.setMessage(lines.joinToString("\n"))
            val other = boxes.firstOrNull { it.host != device.host }
            if (other != null) {
                useBtn.text = "Utiliser ${other.host}"
                useBtn.visibility = View.VISIBLE
                useBtn.setOnClickListener {
                    dialog.dismiss()
                    applyHost(other.host); connected = false; rediscovered = true; errorFlow.value = null
                    lifecycleScope.launch { mutex.withLock { ensureConnected() } }
                }
            }
        }
    }

    private suspend fun sendKey(key: RemoteKey) = mutex.withLock {
        // TV déjà signalée injoignable : on n'envoie plus rien tant que l'utilisateur n'a pas touché « Réessayer »
        // (sauf Marche, qui tente de la réveiller).
        if (offline) {
            if (key == RemoteKey.POWER) { withContext(Dispatchers.Main) { powerOn() }; return@withLock }
            offlineNotice()
            return@withLock
        }
        if (!ensureConnected()) { if (offline) offlineNotice(); return@withLock }
        try {
            if (!driver.send(key)) toast("Touche non disponible sur cet appareil")
        } catch (e: RemoteException) {
            connected = false; connectedFlow.value = false
            driver.close()
            if (isUnreachable(e)) { goOffline(e); return@withLock }
            // Lien simplement coupé : on se reconnecte et on renvoie la touche une fois
            if (ensureConnected()) runCatching { driver.send(key) }.onFailure {
                if (it is RemoteException && isUnreachable(it)) goOffline(it) else showStatus("Erreur : ${it.message}")
            } else if (offline) offlineNotice()
        } catch (e: Exception) {
            if (isUnreachable(e)) { connected = false; connectedFlow.value = false; driver.close(); goOffline(e) }
            else showStatus("Erreur : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** La TV ne répond pas du tout : éteinte, veille profonde ou hors du Wi-Fi (pas une simple coupure du lien). */
    private fun isUnreachable(e: Throwable): Boolean {
        if (e is RemoteException && e.unreachable) return true
        var c: Throwable? = e
        while (c != null) {
            if (c is java.net.ConnectException || c is java.net.NoRouteToHostException || c is java.net.SocketTimeoutException) return true
            c = c.cause
        }
        return false
    }

    /** Signalée injoignable : la carte « En cours » le dit, avec « Réessayer », et les touches ne partent plus. */
    private var unreachableErr = false
    private val offline get() = unreachableErr && errorFlow.value != null && !connected
    private var lastOfflineNotice = 0L

    private suspend fun goOffline(e: Throwable) {
        val msg = e.message ?: "${device.name} ne répond pas."
        fr.paul.telecommande.core.DiagLog.log("TV", "${device.name} injoignable : envoi des touches suspendu (" + msg + ")")
        unreachableErr = true
        errorFlow.value = msg
        showStatus(null)
        offlineNotice()
    }

    /** Rappel discret (au plus toutes les 3 s) : vibration d'erreur + message. */
    private suspend fun offlineNotice() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastOfflineNotice < 3000) return
        lastOfflineNotice = now
        withContext(Dispatchers.Main) {
            window.decorView.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.REJECT else android.view.HapticFeedbackConstants.LONG_PRESS)
            toast("${device.name} ne répond pas : allumez-la, puis touchez « Réessayer »")
        }
    }

    private fun powerOn() {
        lifecycleScope.launch {
            val ok = runCatching { driver.powerOn() }.getOrDefault(false)
            toast(if (ok) "Signal d'allumage envoyé" else "Allumage impossible : renseignez l'adresse MAC de la TV")
            connected = false
        }
    }

    /**
     * Rangée d'applis de l'accueil.
     * Avec l'appli Box : les vraies icônes des applis installées sur la TV (bannières TV quand elles existent).
     * Sinon : raccourcis en pastilles soignées (initiale sur dégradé de la couleur de l'appli).
     */
    private fun appsRow(): View {
        val row = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(40)); addView(row) }
        val real = if (device.hasBoxApp) fr.paul.telecommande.core.AppIcons.featured(this, device.id) else emptyList()
        if (device.hasBoxApp && (real.isEmpty() || fr.paul.telecommande.core.AppIcons.stale(this, device.id)) && !iconsLoading) {
            iconsLoading = true
            lifecycleScope.launch {
                if (fr.paul.telecommande.core.AppIcons.refresh(this@RemoteActivity, device)) { panelSignature = ""; lastSnapshot?.let { render(it) } }
                iconsLoading = false
            }
        }
        if (real.isNotEmpty()) {
            real.forEach { app ->
                val bmp = fr.paul.telecommande.core.AppIcons.icon(this, device.id, app.pkg)
                row.addView(appTile(app.label, bmp, null, null) { box?.send("launch", org.json.JSONObject().put("pkg", app.pkg)); toast("Ouverture de ${app.label}…") })
            }
            row.addView(appTile("Toutes", null, "⋯", color(R.color.surface2)) { showAppsSheet() })
        } else {
            AppCatalog.SHORTCUTS.forEach { sc ->
                row.addView(appTile(sc.label, null, sc.letter, AppCatalog.shortcutBrand(sc.label)) { launchApp(sc.link, sc.label) })
            }
        }
        return scroll
    }
    private var iconsLoading = false

    /** Vignette d'appli 16:9 arrondie, nom dessous. */
    private fun appTile(label: String, icon: android.graphics.Bitmap?, letter: String?, brand: Int?, onClick: () -> Unit): View {
        val w = dp(96); val h = dp(56)
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true; isFocusable = true; contentDescription = "Ouvrir $label"
            setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
        }
        val art: View = if (icon != null) {
            val wide = icon.width > icon.height * 1.3
            android.widget.FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(color(R.color.surface2)); cornerRadius = 14 * d; setStroke(dp(1), color(R.color.stroke)) }
                clipToOutline = true
                addView(android.widget.ImageView(this@RemoteActivity).apply {
                    setImageBitmap(icon)
                    scaleType = if (wide) android.widget.ImageView.ScaleType.CENTER_CROP else android.widget.ImageView.ScaleType.FIT_CENTER
                    if (!wide) setPadding(dp(10), dp(8), dp(10), dp(8))
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
        } else {
            val base = brand ?: color(R.color.accent)
            val dark = android.graphics.Color.rgb((android.graphics.Color.red(base) * 0.62f).toInt(), (android.graphics.Color.green(base) * 0.62f).toInt(), (android.graphics.Color.blue(base) * 0.62f).toInt())
            TextView(this).apply {
                text = letter ?: label.take(1); gravity = Gravity.CENTER; textSize = 22f; typeface = Ui.bold; setTextColor(Color.WHITE)
                background = if (brand == color(R.color.surface2)) GradientDrawable().apply { setColor(base); cornerRadius = 14 * d; setStroke(dp(1), color(R.color.stroke)) }
                    .also { setTextColor(color(R.color.ink)) }
                    else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(base, dark)).apply { cornerRadius = 14 * d }
            }
        }
        tile.addView(art, LinearLayout.LayoutParams(w, h))
        tile.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(color(R.color.ink)); gravity = Gravity.CENTER; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Ui.medium
        }, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(6) })
        tile.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }
        return tile
    }

    private fun launchApp(link: String, label: String) {
        val tv = atv ?: return
        lifecycleScope.launch {
            mutex.withLock {
                if (!ensureConnected()) return@withLock
                runCatching { tv.launchApp(link) }.onSuccess { toast("Ouverture de $label…") }
                    .onFailure { connected = false; connectedFlow.value = false; showStatus("Erreur : ${it.message}") }
            }
        }
    }

    private var keyboard: KeyboardSheet? = null

    /** Clavier du téléphone synchronisé avec la TV (ouvert à la demande ou automatiquement). */
    private fun showKeyboard() {
        if (atv == null && box == null) return
        val k = keyboard ?: KeyboardSheet(this, atv, box).also { keyboard = it }
        k.open()
    }

    /**
     * Bouton « ⋯ » : un volet de tuiles. Outils (clavier, souris, applis…) et, rangées par usage, les autres touches
     * de la TV : chaque groupe s'ouvre en page de grosses touches (le volet reste ouvert pour enchaîner).
     */
    private fun showMoreKeys() {
        val sheet = TileSheet(this)
        val T = TileSheet
        fun key(label: String, k: RemoteKey, color: Int = 0) = TileSheet.Tile("", label, key = true, color = color) { press(k) }
        fun keysPage(emoji: String, title: String, sub: String, cols: Int, keys: List<TileSheet.Tile>) = TileSheet.Page(emoji, title, sub) { sh, col ->
            sh.sections(col, TileSheet.Section(null, keys, cols))
        }
        val digits = keysPage("🔢", "Chiffres et chaînes", "Pour taper un numéro de chaîne", 3,
            (1..9).map { key("$it", RemoteKey.digit(it)) } + listOf(key("CH −", RemoteKey.CH_DOWN), key("0", RemoteKey.D0), key("CH +", RemoteKey.CH_UP)))
        val playback = keysPage("⏯️", "Lecture", "Avancer, reculer, arrêter", 3, listOf(
            key("⏪", RemoteKey.REWIND), key("⏯", RemoteKey.PLAY_PAUSE), key("⏩", RemoteKey.FAST_FORWARD),
            key("⏹", RemoteKey.STOP), key("Muet", RemoteKey.MUTE), key("Infos", RemoteKey.INFO)))
        val colors = keysPage("🎨", "Touches de couleur", "Rouge, vert, jaune, bleu : comme sur la télécommande d'origine", 2, listOf(
            key("Rouge", RemoteKey.RED, 0xFFBE123C.toInt()), key("Vert", RemoteKey.GREEN, 0xFF15803D.toInt()),
            key("Jaune", RemoteKey.YELLOW, 0xFFA16207.toInt()), key("Bleu", RemoteKey.BLUE, 0xFF1D4ED8.toInt())))
        val others = keysPage("🧩", "Autres touches", "Source, menu, guide…", 3, listOf(
            key("Source", RemoteKey.INPUT), key("Menu", RemoteKey.MENU), key("Guide", RemoteKey.GUIDE),
            key("Infos", RemoteKey.INFO), key("Quitter", RemoteKey.EXIT),
            TileSheet.Tile("", "Allumer", key = true) { atv?.allowPowerOnce(); powerOn() }))

        val tools = mutableListOf<TileSheet.Tile>()
        if (atv != null || box != null) tools += TileSheet.Tile("⌨️", "Clavier", "Écrire sur la TV", T.INDIGO) { sheet.dismiss(); showKeyboard() }
        tools += TileSheet.Tile("🖱️", if (touchpad.mouseMode) "Souris : arrêter" else "Mode souris", "Le pavé déplace une flèche", T.SKY) { sheet.dismiss(); setMouse(!touchpad.mouseMode) }
        if (device.isSmartBox) tools += TileSheet.Tile("▦", "Applis de la TV", "Ouvrir Netflix, YouTube…", T.VIOLET) { sheet.dismiss(); showAppsSheet() }
        if (device.hasBoxApp) tools += TileSheet.Tile("⏮️", "Reprendre", "Ce qui a été regardé récemment", T.MINT) { sheet.dismiss(); showContinue() }
        tools += TileSheet.Tile("🔵", "Souris Bluetooth", "Une vraie flèche sur la TV", T.SKY) { sheet.dismiss(); startActivity(Intent(this, BtMouseActivity::class.java)) }

        val keysTiles = listOf(
            TileSheet.Tile("🔢", "Chiffres et chaînes", "0 à 9, CH + / −", T.INDIGO) { sheet.push(digits) },
            TileSheet.Tile("⏯️", "Lecture", "Avance, retour, stop", T.MINT) { sheet.push(playback) },
            TileSheet.Tile("🎨", "Touches de couleur", "Rouge, vert, jaune, bleu", T.ROSE) { sheet.push(colors) },
            TileSheet.Tile("🧩", "Autres touches", "Source, menu, guide…", T.AMBER) { sheet.push(others) })

        val remote = mutableListOf(
            TileSheet.Tile("⚙️", "Réglages", "De cette télécommande", T.INDIGO) { sheet.push(settingsPage()) },
            TileSheet.Tile("❓", "Aide", "Les gestes du pavé", T.MINT) { sheet.dismiss(); showHelp() })
        if (AdminMenu.enabled(this)) remote += TileSheet.Tile("🛠️", "Administrateur", "Outils avancés", T.AMBER) { sheet.dismiss(); showAdmin() }

        sheet.show(TileSheet.Page("✨", "Plus", "Outils et autres touches de ${device.name}") { sh, col ->
            sh.sections(col,
                TileSheet.Section("Outils", tools),
                TileSheet.Section("Touches de la TV", keysTiles),
                TileSheet.Section("La télécommande", remote))
        })
    }

    // ---------- Navigation, menu, touches répétées ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun repeatKey(id: Int, key: RemoteKey) {
        val v = findViewById<View>(id)
        val repeater = object : Runnable {
            override fun run() { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); press(key); v.postDelayed(this, 170) }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.isPressed = true; view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); press(key); view.postDelayed(repeater, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false; view.removeCallbacks(repeater)
                    if (e.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            true
        }
    }

    /** La télécommande n'a plus de barre du bas : on y revient par le bouton rond central de l'appli. */
    private fun setupNav() {}

    private fun showAppsSheet() {
        if (box != null) { BoxActions.appsSheet(this, device); return }
        val sheet = TileSheet(this)
        sheet.show(TileSheet.Page("▦", "Applis", "Ouvrir une appli sur ${device.name}") { sh, col ->
            sh.sections(col, TileSheet.Section(null, AppCatalog.SHORTCUTS.map { sc ->
                TileSheet.Tile(sc.letter, sc.label, "Ouvrir sur la TV", AppCatalog.shortcutBrand(sc.label) and 0x00FFFFFF or 0x40000000) {
                    sheet.dismiss(); launchApp(sc.link, sc.label)
                }
            }, 3))
        })
    }

    /** Menu ⋮ : seulement deux entrées. Le contrôle parental est dans l'onglet du bas, les touches et la souris dans « ⋯ ». */
    private fun showMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add("Réglages"); menu.menu.add("Aide et dépannage")
        menu.setOnMenuItemClickListener {
            when (it.title.toString()) {
                "Réglages" -> showSettings()
                "Aide et dépannage" -> showHelp()
            }
            true
        }
        menu.show()
    }

    /** Menu caché Administrateur, avec les outils propres à la connexion en cours. */
    private fun showAdmin() {
        val voiceFile = java.io.File(filesDir, "voix-derniere.wav")
        AdminMenu.open(this, device, AdminMenu.RemoteTools(
            diagnostic = { showDiagnostic() },
            editHost = { editHost() },
            replayVoice = if (voiceFile.exists()) ({
                runCatching {
                    android.media.MediaPlayer().apply { setDataSource(voiceFile.path); setOnCompletionListener { it.release() }; prepare(); start() }
                    Ui.toast(this, "Lecture de ce que la TV a reçu…")
                }.onFailure { Ui.toast(this, "Lecture impossible") }
                Unit
            }) else null
        ))
    }

    /** Réglages de la télécommande, en tuiles qui basculent d'un geste (page du volet « Plus »). */
    private fun settingsPage(): TileSheet.Page = TileSheet.Page("⚙️", "Réglages", "De la télécommande de ${device.name}") { sh, col ->
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val voicePhone = prefs.getBoolean("voice_phone", false)
        val quick = fr.paul.telecommande.service.ControlNotification.enabled(this)
        sh.sections(col, TileSheet.Section(null, listOf(
            TileSheet.Tile("🎙️", "Recherche vocale", if (voicePhone) "Le téléphone écoute" else "La TV écoute", TileSheet.VIOLET) {
                prefs.edit().putBoolean("voice_phone", !voicePhone).apply()
                Ui.toast(this, if (!voicePhone) "Le téléphone reconnaît la voix et tape la recherche sur la TV" else "La voix est envoyée à l'Assistant de la TV")
                sh.refresh()
            },
            TileSheet.Tile("⚡", "Réglages rapides", if (quick) "Télécommande présente" else "Absente", TileSheet.AMBER) {
                if (quick) {
                    fr.paul.telecommande.service.ControlNotification.setEnabled(this, false); Ui.toast(this, "Retirée des réglages rapides")
                } else {
                    fr.paul.telecommande.service.ControlNotification.setEnabled(this, true)
                    if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 12)
                    fr.paul.telecommande.service.ControlNotification.show(this, device.id)
                    Ui.toast(this, "Télécommande ajoutée au lecteur des réglages rapides")
                }
                sh.refresh()
            },
            TileSheet.Tile("🌐", "Adresse de l'appareil", "Si la TV n'est pas trouvée", TileSheet.SKY) { sh.dismiss(); editHost() }
        )))
    }

    /** Ancien menu de réglages (gardé pour les appels existants) : ouvre la page du volet. */
    private fun showSettings() { TileSheet(this).show(settingsPage()) }

    private fun showHelp() {
        val voiceFile = java.io.File(filesDir, "voix-derniere.wav")
        val items = mutableListOf("Diagnostic de connexion", "Journal de diagnostic", "Rechercher une mise à jour")
        if (voiceFile.exists()) items.add("Réécouter la dernière recherche vocale")
        AlertDialog.Builder(this).setTitle("Aide et dépannage").setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "Diagnostic de connexion" -> showDiagnostic()
                "Journal de diagnostic" -> showJournal()
                "Rechercher une mise à jour" -> PhoneUpdate.checkAndOffer(this, force = true)
                "Réécouter la dernière recherche vocale" -> runCatching {
                    android.media.MediaPlayer().apply { setDataSource(voiceFile.path); setOnCompletionListener { it.release() }; prepare(); start() }
                    Ui.toast(this, "Lecture de ce que la TV a reçu…")
                }.onFailure { Ui.toast(this, "Lecture impossible") }
            }
        }.setNegativeButton("Fermer", null).show()
    }

    private fun showJournal() {
        AlertDialog.Builder(this).setTitle("Journal de diagnostic")
            .setMessage("Le téléphone note tout ce qu'il envoie à la TV et ce qu'elle répond ; le journal de la TV (ce qu'elle lit à l'écran et ce qu'elle décide) y est ajouté à l'envoi. En cas de problème, envoyez ce journal pour qu'il soit analysé. Il reste sur ce téléphone tant que vous ne l'envoyez pas.")
            .setPositiveButton("Analyser avec Claude") { _, _ -> fr.paul.telecommande.core.DiagLog.export(this, toClaude = true) }
            .setNegativeButton("Envoyer", ) { _, _ -> fr.paul.telecommande.core.DiagLog.export(this) }
            .setNeutralButton("Effacer") { _, _ -> fr.paul.telecommande.core.DiagLog.clear(); toast("Journal effacé") }
            .show()
    }

    /** Changer d'appareil sans repasser par la liste. */
    /** Pilule du nom de la TV : changer d'écran, en tuiles. */
    private fun showDeviceSwitcher() {
        val all = store.all()
        val sheet = TileSheet(this)
        sheet.show(TileSheet.Page("📺", "Quel écran ?", "Touchez l'écran à piloter") { sh, col ->
            sh.sections(col, TileSheet.Section(null, all.map { d ->
                TileSheet.Tile("📺", d.name, if (d.id == device.id) "Utilisé maintenant" else d.type.label.substringBefore(" ("),
                    if (d.id == device.id) TileSheet.MINT else TileSheet.INDIGO) {
                    sheet.dismiss()
                    if (d.id != device.id) { startActivity(Intent(this, RemoteActivity::class.java).putExtra(EXTRA_ID, d.id)); finish() }
                }
            } + TileSheet.Tile("🏠", "Revenir à l'appli", "Fermer la télécommande", TileSheet.AMBER) { sheet.dismiss(); closeRemote() }))
        })
    }

    // ---------- Recherche vocale ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMic() {
        val mic = findViewById<View>(R.id.btnMic)
        if (atv == null) {
            // Pas de recherche vocale sur cette TV : le gros bouton central ouvre « Plus » (touches, clavier, réglages)
            (mic as? android.widget.ImageButton)?.setImageResource(R.drawable.ic_more)
            mic.contentDescription = "Plus de touches"
            mic.setOnClickListener { showMoreKeys() }
            return
        }
        // Maintenir = parler tant que le doigt reste posé ; toucher = parler jusqu'au silence.
        mic.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (recorder != null) recorder?.stop()
                    else { micDownAt = SystemClock.uptimeMillis(); startVoice() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (SystemClock.uptimeMillis() - micDownAt > 600) recorder?.stop()
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    private fun startVoice() {
        val tv = atv ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 8)
            return
        }
        if (voiceJob?.isActive == true) return
        voiceJob = lifecycleScope.launch {
            val ok = mutex.withLock { ensureConnected() }
            if (!ok) return@launch
            if (getSharedPreferences("ui", MODE_PRIVATE).getBoolean("voice_phone", false)) { phoneRecognition(); return@launch }
            val session = runCatching { if (tv.voiceSupported) tv.startVoice() else null }.getOrNull()
            if (session == null) { phoneRecognition(); return@launch }
            val rec = VoiceRecorder()
            recorder = rec
            val mic = findViewById<View>(R.id.btnMic)
            mic.backgroundTintList = ColorStateList.valueOf(color(R.color.power))
            showStatus("Parlez maintenant… (relâchez ou faites une pause pour envoyer)")
            val anim = launch { while (isActive) { val s = 1f + rec.level * 0.25f; mic.scaleX = s; mic.scaleY = s; delay(60) } }
            try {
                rec.record({ chunk -> tv.sendVoice(session, chunk) }, autoStopOnSilence = true)
                showStatus(null)
            } catch (e: Exception) {
                showStatus("Micro : ${e.message ?: "erreur"}")
            } finally {
                tv.endVoice(session)
                runCatching { rec.saveWav(java.io.File(filesDir, "voix-derniere.wav")) }
                fr.paul.telecommande.core.DiagLog.log("VOIX", "Envoyé à la TV : ${rec.durationMs / 100 / 10.0} s · micro ${rec.sampleRate} Hz · crête ${"%.0f".format(rec.peakDb)} dB · gain ×${"%.1f".format(rec.gain)} · parole " + if (rec.spoke) "détectée" else "non détectée")
                anim.cancel()
                mic.scaleX = 1f; mic.scaleY = 1f
                mic.backgroundTintList = null
                recorder = null
            }
        }
    }

    private fun phoneRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Que voulez-vous chercher sur la TV ?")
        runCatching { speechLauncher.launch(intent) }
            .onFailure { toast("Reconnaissance vocale indisponible sur ce téléphone") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toast("Micro autorisé : appuyez à nouveau sur le micro")
            else toast("Sans le micro, la recherche vocale est impossible")
        }
    }

    // ---------- YouTube ----------

    private fun linkYoutube() {
        val a = assistant ?: return
        lifecycleScope.launch {
            showStatus("Recherche de YouTube sur la box…")
            val screenId = DialScreenId.find(this@RemoteActivity, device.host)
            val ok = screenId != null && runCatching { a.pairWithScreenId(screenId) }.getOrDefault(false)
            if (!ok) {
                showStatus(null)
                val code = askCode(
                    "Associer YouTube",
                    "Sur la TV : ouvrez YouTube > Paramètres > « Associer un appareil » (ou « Associer avec un code TV »), puis saisissez le code bleu à 12 chiffres.",
                    "123 456 789 012"
                ) ?: return@launch
                try {
                    showStatus("Association…")
                    a.pairWithCode(code)
                } catch (e: Exception) {
                    showStatus("Association impossible : ${e.message}"); return@launch
                }
            }
            showStatus(null)
            store.save(device)
            toast("YouTube associé : les pubs seront passées automatiquement")
            askNotificationPermission()
            AssistantService.start(this@RemoteActivity)
            panelSignature = ""
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    // ---------- Divers ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VOL_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VOL_DOWN
            else -> return super.onKeyDown(keyCode, event)
        }
        press(key); return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true else super.onKeyUp(keyCode, event)

    private suspend fun showStatus(text: String?) = withContext(Dispatchers.Main) {
        if (isFinishing) return@withContext
        status.text = text.orEmpty()
        status.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun info(message: String) {
        runOnUiThread { status.text = message; status.visibility = View.VISIBLE; Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override suspend fun askCode(title: String, message: String, hint: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val input = EditText(this@RemoteActivity).apply {
                this.hint = hint
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            val box = FrameLayout(this@RemoteActivity).apply { setPadding(dp(20), 0, dp(20), 0); addView(input) }
            val dialog = AlertDialog.Builder(this@RemoteActivity)
                .setTitle(title).setMessage(message).setView(box)
                .setPositiveButton("Valider") { _, _ -> if (cont.isActive) cont.resume(input.text.toString().trim().ifBlank { null }) }
                .setNegativeButton("Annuler") { _, _ -> if (cont.isActive) cont.resume(null) }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                .create()
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
            dialog.show()
        }
    }

    override fun onPause() {
        super.onPause(); MouseHold.stopAll(); holdJob?.cancel(); heldArrow = null
        atv?.releaseAllHeld(); touchpad.mouseMode = touchpad.mouseMode
    }

    // Notification de contrôle : visible dès qu'on quitte l'écran télécommande, masquée quand on y revient
    // Télécommande dans le lecteur des réglages rapides : lancée tant que l'appli est visible (exigence d'Android),
    // elle reste ensuite disponible appli fermée, jusqu'à ce qu'on la ferme (✕) ou qu'on la désactive dans le menu.
    override fun onStart() {
        super.onStart()
        if (::device.isInitialized) fr.paul.telecommande.service.ControlNotification.show(this, device.id)
    }

    /** Programmes en cours dans les applis de la TV (lus par l'appli Box) : un appui et ça reprend sur la TV. */
    private fun showContinue() {
        lifecycleScope.launch {
            val res = runCatching { fr.paul.telecommande.core.BoxClient.of(this@RemoteActivity, device).call("continue_watching") }
            val items = res.getOrNull()?.optJSONArray("items")
            if (items == null) {
                Ui.toast(this@RemoteActivity, res.getOrNull()?.optString("error")?.ifBlank { null } ?: "La TV ne répond pas (mettez l'appli Box à jour)")
                return@launch
            }
            if (items.length() == 0) { Ui.toast(this@RemoteActivity, "Rien à reprendre pour l'instant"); return@launch }
            val list = (0 until items.length()).map { items.getJSONObject(it) }
            val lines = list.map { o ->
                o.optString("title") + "\n" + listOf(o.optString("episode"), o.optString("label")).filter { it.isNotBlank() }.joinToString(" · ")
            }.toTypedArray()
            AlertDialog.Builder(this@RemoteActivity).setTitle("Reprendre")
                .setItems(lines) { _, i ->
                    lifecycleScope.launch {
                        val r = runCatching { fr.paul.telecommande.core.BoxClient.of(this@RemoteActivity, device).call("open_row", org.json.JSONObject().put("id", list[i].optString("id"))) }.getOrNull()
                        Ui.toast(this@RemoteActivity, if (r?.optBoolean("ok") == true) "Reprise de « ${list[i].optString("title")} »…" else r?.optString("error")?.ifBlank { null } ?: "La TV ne répond pas")
                    }
                }.setNegativeButton("Fermer", null).show()
        }
    }

    override fun onDestroy() {
        recorder?.stop()
        if (::device.isInitialized && !DriverHub.hasBackgroundWork()) DriverHub.release(device.id)
        super.onDestroy()
    }
}

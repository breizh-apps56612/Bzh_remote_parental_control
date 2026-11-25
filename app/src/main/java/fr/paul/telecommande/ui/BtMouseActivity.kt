package fr.paul.telecommande.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.BtMouse
import fr.paul.telecommande.core.DiagLog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Vraie souris : le téléphone devient une souris Bluetooth pour le Player.
 * Une flèche apparaît sur la TV et se déplace exactement comme avec une souris branchée.
 */
@SuppressLint("MissingPermission")
class BtMouseActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var padHost: LinearLayout
    private val found = linkedMapOf<String, BluetoothDevice>()
    private var scanning = false
    private var builtConnected: Boolean? = null

    // Guide d'ajout (cases cochées automatiquement quand c'est détecté)
    private var seenVisible = false
    private var pairedFromTv = false
    private val manualDone = mutableSetOf<String>()

    private val perms: Array<String> get() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPerms() = perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null && !d.name.isNullOrBlank()) { found[d.address] = d; render() }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { scanning = false; render() }
                BluetoothAdapter.ACTION_STATE_CHANGED -> { BtMouse.start(this@BtMouseActivity); render() }
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, 0) == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) seenVisible = true
                    render()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0)
                    DiagLog.log("BT", "Relation Bluetooth : état $st")
                    if (st == BluetoothDevice.BOND_BONDED && seenVisible) {
                        pairedFromTv = true; manualDone += "forgetTv"
                        getSharedPreferences("btmouse", MODE_PRIVATE).edit().putBoolean("pairedFromTv", true).apply()
                    }
                    render()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.systemBars(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = this
        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(c, 16); setPadding(p, p, p, p) }
        padHost = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        setContentView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_screen)
            addView(ScrollView(c).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(padHost, LinearLayout.LayoutParams(-1, -2))
        })
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        pairedFromTv = getSharedPreferences("btmouse", MODE_PRIVATE).getBoolean("pairedFromTv", false)
        if (hasPerms()) BtMouse.start(this)
        lifecycleScope.launch { BtMouse.state.combine(BtMouse.hostName) { a, b -> a to b }.collect { render() } }
        lifecycleScope.launch {
            BtMouse.refused.collect { r ->
                if (r) { pairedFromTv = false; getSharedPreferences("btmouse", MODE_PRIVATE).edit().putBoolean("pairedFromTv", false).apply() }
                render()
            }
        }
        render()
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized) render() }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        if (scanning) runCatching { BtMouse.adapter(this)?.cancelDiscovery() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) BtMouse.start(this)
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        BtMouse.start(this); render()
    }

    // ---------------- Affichage ----------------

    private fun render() {
        val c = this
        val st = BtMouse.state.value
        content.removeAllViews()
        content.addView(Ui.title(c, "Vraie souris"))
        content.addView(Ui.text(c, "Le téléphone devient une souris Bluetooth : une flèche apparaît sur la TV, comme avec une souris branchée.", 15f, muted = true), Ui.lp(c, 6))

        when {
            !BtMouse.supported || st == BtMouse.State.UNSUPPORTED ->
                content.addView(card("Non disponible", "Ce téléphone ne peut pas servir de souris Bluetooth (Android 9 minimum, et le fabricant doit l'autoriser)."), Ui.lp(c, 16))
            !hasPerms() -> content.addView(card("Autoriser le Bluetooth", "Nécessaire pour que la TV voie le téléphone comme une souris.", "Autoriser") {
                @Suppress("DEPRECATION") requestPermissions(perms, 7)
            }, Ui.lp(c, 16))
            st == BtMouse.State.OFF -> content.addView(card("Activer le Bluetooth", "Le Bluetooth du téléphone est coupé.", "Activer") {
                @Suppress("DEPRECATION") startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 8)
            }, Ui.lp(c, 16))
            st == BtMouse.State.CONNECTED -> {
                content.addView(statusPill("Connectée à ${BtMouse.hostName.value}", true), Ui.lp(c, 16).apply { width = -2 })
                content.addView(Ui.text(c, "Glissez un doigt pour déplacer la flèche, touchez pour cliquer, deux doigts pour faire défiler.", 14f, muted = true), Ui.lp(c, 10))
                content.addView(Ui.button(c, "Déconnecter la souris") { BtMouse.disconnect() }, LinearLayout.LayoutParams(-1, Ui.dp(c, 48)).apply { topMargin = Ui.dp(c, 12) })
            }
            st == BtMouse.State.REGISTERING || st == BtMouse.State.CONNECTING || st == BtMouse.State.IDLE ->
                content.addView(LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ProgressBar(c), LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)))
                    addView(Ui.text(c, if (st == BtMouse.State.CONNECTING) "Connexion à la TV…" else "Préparation de la souris…", 15f),
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(c, 12) })
                }, Ui.lp(c, 20))
            else -> renderSetup()
        }
        renderPad(st == BtMouse.State.CONNECTED)
    }

    /** Une étape du guide : titre, explication, bouton éventuel, et si elle est faite. */
    private class GuideStep(val id: String, val title: String, val help: String, val done: Boolean,
                            val action: String? = null, val run: (() -> Unit)? = null, val manual: String? = "C'est fait")

    private fun tvBonded(): BluetoothDevice? {
        val bonded = runCatching { BtMouse.adapter(this)?.bondedDevices?.toList() }.getOrNull().orEmpty()
        val saved = BtMouse.savedHost(this)
        return bonded.firstOrNull { it.address == saved }
            ?: bonded.firstOrNull { d -> d.name.orEmpty().lowercase().let { it.contains("freebox") || it.contains("player") } }
    }

    private fun renderSetup() {
        val c = this
        val name = runCatching { BtMouse.adapter(c)?.name }.getOrNull() ?: "ce téléphone"
        val visibleNow = runCatching { BtMouse.adapter(c)?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE }.getOrDefault(false)
        if (visibleNow) seenVisible = true
        val old = if (pairedFromTv) null else tvBonded()

        val steps = listOf(
            GuideStep("forgetPhone", "Supprimer l'ancien lien sur le téléphone",
                "Le téléphone est déjà relié à « ${old?.name ?: "la TV"} » comme un téléphone : la TV refuse alors la souris. " +
                    "Ouvrez le Bluetooth, touchez ⚙ à côté de « ${old?.name ?: "Freebox Player POP"} », puis « Supprimer ». Cette case se coche toute seule.",
                done = old == null || "forgetPhone" in manualDone,
                action = "Ouvrir le Bluetooth du téléphone", run = { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }),
            GuideStep("forgetTv", "Oublier le téléphone sur la TV",
                "Avec la télécommande du Player : Paramètres › Télécommandes et accessoires. Si « $name » apparaît, choisissez-le puis « Oublier ». S'il n'apparaît pas, passez à la suite.",
                done = "forgetTv" in manualDone, manual = "C'est fait / il n'apparaît pas"),
            GuideStep("visible", "Rendre le téléphone visible",
                "Le téléphone devient visible pour la TV pendant 5 minutes. Restez sur cet écran.",
                done = seenVisible,
                action = "Rendre visible", run = {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), 9)
                }, manual = null),
            GuideStep("addTv", "Ajouter le téléphone depuis la TV",
                "Sur la TV : Paramètres › Télécommandes et accessoires › Ajouter un accessoire, puis choisissez « $name ». " +
                    "Acceptez le code sur la TV ET sur le téléphone. La souris se connecte ensuite toute seule.",
                done = pairedFromTv || BtMouse.state.value == BtMouse.State.CONNECTED, manual = null)
        )
        val cur = steps.indexOfFirst { !it.done }.let { if (it < 0) steps.size else it }

        if (BtMouse.refused.value) content.addView(Ui.text(c, "La TV a refusé la souris : suivez les étapes ci-dessous pour refaire le lien.", 14f).apply {
            setTextColor(Ui.color(c, R.color.danger))
        }, Ui.lp(c, 14))

        val tile = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.color(c, R.color.surface)); cornerRadius = 22 * resources.displayMetrics.density
                setStroke(Ui.dp(c, 2), Ui.color(c, R.color.accent))
            }
            setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16))
        }
        tile.addView(TextView(c).apply { text = "Ajouter la souris"; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.color(c, R.color.ink)) })
        tile.addView(Ui.text(c, if (cur >= steps.size) "Connexion de la souris…" else "Étape ${cur + 1} sur ${steps.size}", 13f, muted = true), Ui.lp(c, 2))
        steps.forEachIndexed { i, st -> tile.addView(guideRow(i, st, i < cur || st.done, i == cur, i == steps.lastIndex), Ui.lp(c, if (i == 0) 14 else 0)) }
        content.addView(tile, Ui.lp(c, 16))

        // Reconnexion rapide, pour un téléphone déjà ajouté depuis la TV
        content.addView(sectionTitle("Déjà ajouté depuis la TV ? Se reconnecter"), Ui.lp(c, 24))
        val adapter = BtMouse.adapter(c)
        val bonded = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
        val all = (bonded + found.values).distinctBy { it.address }
            .sortedByDescending { d -> val n = d.name.orEmpty().lowercase(); n.contains("freebox") || n.contains("player") || n.contains("tv") }
        all.take(6).forEach { d -> content.addView(deviceRow(d, d in bonded), Ui.lp(c, 8)) }
        content.addView(Ui.button(c, if (scanning) "Recherche en cours…" else "Rechercher les appareils") {
            if (!scanning) { scanning = runCatching { adapter?.startDiscovery() == true }.getOrDefault(false); render() }
        }, LinearLayout.LayoutParams(-1, Ui.dp(c, 48)).apply { topMargin = Ui.dp(c, 10) })
    }

    private fun guideRow(i: Int, st: GuideStep, done: Boolean, isCur: Boolean, last: Boolean): View {
        val c = this
        val row = LinearLayout(c)
        val rail = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        rail.addView(TextView(c).apply {
            text = if (done) "✓" else "${i + 1}"; gravity = Gravity.CENTER; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (done || isCur) android.graphics.Color.WHITE else Ui.color(c, R.color.muted))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.color(c, when { done -> R.color.ok; isCur -> R.color.accent; else -> R.color.surface2 }))
            }
        }, LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)))
        if (!last) rail.addView(View(c).apply { setBackgroundColor(Ui.color(c, if (done) R.color.ok else R.color.line)) },
            LinearLayout.LayoutParams(Ui.dp(c, 2), 0, 1f).apply { topMargin = Ui.dp(c, 4); bottomMargin = Ui.dp(c, 4) })
        row.addView(rail, LinearLayout.LayoutParams(Ui.dp(c, 24), -1))

        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 12), Ui.dp(c, 1), 0, Ui.dp(c, if (last) 0 else 14)) }
        body.addView(TextView(c).apply {
            text = st.title; textSize = if (isCur) 16f else 15f
            setTextColor(Ui.color(c, when { done -> R.color.ok; isCur -> R.color.ink; else -> R.color.muted }))
            if (isCur) setTypeface(typeface, Typeface.BOLD)
        })
        if (isCur) {
            body.addView(Ui.text(c, st.help, 14f, muted = true), Ui.lp(c, 4))
            if (st.id == "addTv") body.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ProgressBar(c), LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)))
                addView(Ui.text(c, "En attente de la TV…", 13f, muted = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(c, 8) })
            }, Ui.lp(c, 10))
            st.action?.let { l -> body.addView(Ui.button(c, l, primary = true) { st.run?.invoke() }, LinearLayout.LayoutParams(-1, Ui.dp(c, 50)).apply { topMargin = Ui.dp(c, 12) }) }
            st.manual?.let { l -> body.addView(Ui.button(c, l, primary = st.action == null) { manualDone += st.id; DiagLog.log("BT", "Guide : ${st.id} validé à la main"); render() },
                LinearLayout.LayoutParams(-1, Ui.dp(c, 50)).apply { topMargin = Ui.dp(c, 8) }) }
        }
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun renderPad(connected: Boolean) {
        if (builtConnected == connected) return
        builtConnected = connected
        padHost.removeAllViews()
        if (!connected) return
        val c = this
        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.color(c, R.color.surface)); val r = 26 * resources.displayMetrics.density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16))
        }
        val pad = TouchpadView(c).apply {
            mouseMode = true
            rawMoveListener = { dx, dy -> BtMouse.move(dx, dy) }
            scrollListener = { n -> BtMouse.scroll(-n) }
            listener = { g -> if (g == TouchpadView.Gesture.TAP) BtMouse.click(1) }
        }
        panel.addView(pad, LinearLayout.LayoutParams(-1, Ui.dp(c, 300)))
        panel.addView(LinearLayout(c).apply {
            addView(Ui.button(c, "↩  Retour") { BtMouse.click(2) }, LinearLayout.LayoutParams(0, Ui.dp(c, 54), 1f).apply { marginEnd = Ui.dp(c, 8) })
            addView(Ui.button(c, "Clic", primary = true) { BtMouse.click(1) }, LinearLayout.LayoutParams(0, Ui.dp(c, 54), 1.4f))
        }, Ui.lp(c, 12))
        padHost.addView(panel)
    }

    // ---------------- Éléments ----------------

    private fun sectionTitle(t: String) = TextView(this).apply { text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.color(this@BtMouseActivity, R.color.ink)) }

    private fun card(title: String, body: String, action: String? = null, onClick: (() -> Unit)? = null): View {
        val c = this
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Ui.color(c, R.color.surface)); cornerRadius = 20 * resources.displayMetrics.density }
            setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16))
            addView(TextView(c).apply { text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.color(c, R.color.ink)) })
            addView(Ui.text(c, body, 14f, muted = true), Ui.lp(c, 6))
            if (action != null && onClick != null) addView(Ui.button(c, action, primary = true) { onClick() }, LinearLayout.LayoutParams(-1, Ui.dp(c, 50)).apply { topMargin = Ui.dp(c, 12) })
        }
    }

    private fun step(n: Int, title: String, body: String, action: String? = null, onClick: (() -> Unit)? = null): View {
        val c = this
        return LinearLayout(c).apply {
            background = GradientDrawable().apply { setColor(Ui.color(c, R.color.surface)); cornerRadius = 20 * resources.displayMetrics.density }
            setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14))
            addView(TextView(c).apply {
                text = "$n"; gravity = Gravity.CENTER; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(android.graphics.Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.color(c, R.color.accent)) }
            }, LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26)))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(c).apply { text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.color(c, R.color.ink)) })
                addView(Ui.text(c, body, 14f, muted = true), Ui.lp(c, 4))
                if (action != null && onClick != null) addView(Ui.button(c, action, primary = true) { onClick() }, LinearLayout.LayoutParams(-1, Ui.dp(c, 48)).apply { topMargin = Ui.dp(c, 10) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(c, 12) })
        }
    }

    private fun deviceRow(d: BluetoothDevice, bonded: Boolean): View {
        val c = this
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Ui.color(c, R.color.surface)); cornerRadius = 16 * resources.displayMetrics.density }
            setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(c).apply { text = d.name ?: d.address; textSize = 15f; setTextColor(Ui.color(c, R.color.ink)) })
                addView(Ui.text(c, if (bonded) "Déjà relié" else "Trouvé à proximité", 12f, muted = true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.button(c, "Connecter", primary = true) {
                DiagLog.log("BT", "Connexion demandée vers ${d.name} (${d.address})")
                runCatching { BtMouse.adapter(c)?.cancelDiscovery() }; scanning = false
                BtMouse.connect(c, d.address)
            }, LinearLayout.LayoutParams(-2, Ui.dp(c, 44)))
        }
    }

    private fun statusPill(t: String, ok: Boolean) = TextView(this).apply {
        text = "●  $t"; textSize = 14f; setTextColor(Ui.color(this@BtMouseActivity, if (ok) R.color.ok else R.color.muted))
        background = GradientDrawable().apply { setColor(Ui.color(this@BtMouseActivity, R.color.surface)); cornerRadius = 99f * resources.displayMetrics.density }
        setPadding(Ui.dp(this@BtMouseActivity, 12), Ui.dp(this@BtMouseActivity, 6), Ui.dp(this@BtMouseActivity, 12), Ui.dp(this@BtMouseActivity, 6))
    }
}

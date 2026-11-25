package fr.paul.telecommande.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.ApkServer
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DriverHub
import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.drivers.AndroidTvDriver
import fr.paul.telecommande.drivers.PairingUi
import fr.paul.telecommande.shared.BoxProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Assistant d'installation de l'appli Box.
 *
 * Règle d'or : le téléphone n'envoie RIEN à la TV sans un appui de l'utilisateur.
 * Il se contente d'observer (appli ouverte sur la TV, clavier affiché, fichier téléchargé)
 * pour cocher les cases tout seul. Chaque action est un bouton, une étape à la fois.
 */
class InstallBoxActivity : AppCompatActivity(), PairingUi {
    companion object {
        const val EXTRA_ID = "device_id"
        const val DOWNLOADER = "com.esaba.downloader"
        const val BOX = "fr.paul.telecommande.box"
        val STORE = setOf("com.android.vending")
    }

    /** Une ligne de la liste : ce qu'il faut faire, le bouton qui le fait, et comment on sait que c'est fait. */
    private class Sub(
        val id: String,
        val label: String,
        val help: String,
        val actionLabel: String? = null,
        val action: (suspend () -> Unit)? = null,
        val manualLabel: String = "C'est fait",
        val detect: () -> Boolean = { false }
    )

    private class Step(val title: String, val subs: List<Sub>)

    private lateinit var device: Device
    private var tv: AndroidTvDriver? = null
    private lateinit var server: ApkServer
    private lateinit var content: LinearLayout
    private lateinit var remoteHost: FrameLayout

    private val manual = mutableSetOf<String>()      // lignes validées à la main (« C'est fait »)
    private var tvApp = ""
    private var tvConnected = false
    private var boxUp = false
    private var busy: String? = null                  // ligne dont l'action est en cours
    private var note = ""                             // message ponctuel sous la ligne en cours

    // Observations (lecture seule)
    private var imeBaseline = -1
    private var barOpen = false
    private var urlSet = false

    private var mouse = false
    private var userPickedMode = false
    private var builtRemote: String? = null

    private lateinit var steps: List<Step>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra(EXTRA_ID)?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        Ui.systemBars(this)
        tv = DriverHub.driver(this, device) as? AndroidTvDriver
        server = ApkServer(this)
        steps = buildSteps()

        val c = this
        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(c, 16); setPadding(p, p, p, p) }
        remoteHost = FrameLayout(c)
        setContentView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_screen)
            addView(ScrollView(c).apply { addView(content); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(remoteHost, LinearLayout.LayoutParams(-1, -2))
        })
        if (ApkServer.bundled(this)) server.start()   // serveur local du téléphone (n'agit pas sur la TV)
        render()

        // Observation seulement : connexion pour savoir quelle appli est ouverte sur la TV
        lifecycleScope.launch {
            tvConnected = runCatching { tv?.connect(this@InstallBoxActivity); tv?.isConnected == true }.getOrDefault(false)
            render()
            tv?.currentApp?.collect { tvApp = it; if (!userPickedMode) mouse = it in MouseHold.CURSOR_APPS; render() }
        }
        lifecycleScope.launch { server.state.collect { render() } }
        lifecycleScope.launch { fr.paul.telecommande.core.BtMouse.state.collect { render() } }
        lifecycleScope.launch {
            val t = tv ?: return@launch
            t.imeShown.collect { n ->
                if (imeBaseline < 0) { imeBaseline = n; return@collect }
                if (n > imeBaseline && tvApp == DOWNLOADER) { imeBaseline = n; barOpen = true; urlSet = false; render() }
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                val s = currentStepIndex()
                if (s in 2..3 && !boxUp) { boxUp = boxReachable(); if (boxUp) render() }
                delay(3_000)
            }
        }
    }

    override fun onPause() { super.onPause(); MouseHold.stopAll(); tv?.releaseAllHeld() }

    private var serverOk: Boolean? = null

    override fun onResume() {
        super.onResume()
        // Le serveur du téléphone doit tourner (même adresse) tant que l'assistant est ouvert
        if (::server.isInitialized && ApkServer.bundled(this)) {
            if (!server.running) { DiagLog.log("APK", "Redémarrage du serveur"); server.start() }
            lifecycleScope.launch { serverOk = server.selfTest(); render() }
        }
    }
    override fun onDestroy() { if (::server.isInitialized) server.stop(); super.onDestroy() }

    private suspend fun boxReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(device.host, BoxProtocol.PORT), 1500) }; true }.getOrDefault(false)
    }

    // ---------------- Étapes ----------------

    private fun st() = server.state.value
    private fun received() = st() == ApkServer.State.DONE

    private fun buildSteps() = listOf(
        Step("Installer Downloader", listOf(
            Sub("store", "Afficher Downloader sur la TV",
                "La page de Downloader s'ouvre dans le Play Store de la TV.",
                "Afficher sur la TV", { tv?.launchApp("market://details?id=$DOWNLOADER") },
                detect = { tvApp in STORE || tvApp == DOWNLOADER || received() }),
            Sub("dl_install", "Installer puis ouvrir Downloader",
                "Sur la TV, choisissez « Installer », puis « Ouvrir ». Utilisez la télécommande en bas de l'écran.",
                manualLabel = "Downloader est ouvert",
                detect = { tvApp == DOWNLOADER || received() })
        )),
        Step("Télécharger l'appli Box", listOf(
            // Downloader 2 accepte les liens « downloader://adresse » : la TV ouvre Downloader ET charge l'adresse, sans rien taper.
            Sub("direct", "Envoyer l'appli à Downloader",
                "Downloader s'ouvre sur la TV et télécharge l'appli tout seul. Si Downloader affiche une page, cliquez sur « Download ».",
                "Envoyer à la TV", { tv?.launchApp("downloader://" + shortUrl()) },
                manualLabel = "Ça n'a pas marché : saisie manuelle",
                detect = { st() == ApkServer.State.DOWNLOADING || received() }),
            // Pas de lancement à distance : sur certains Players, ouvrir Downloader par lien fait planter la box.
            Sub("open", "Ouvrir Downloader sur la TV",
                "Revenez à l'accueil de la TV, allez dans « Applications » avec les flèches, puis ouvrez Downloader avec OK. Cette ligne se coche toute seule.",
                "Aller à l'accueil de la TV", { tv?.send(RemoteKey.HOME) },
                manualLabel = "Downloader est ouvert",
                detect = { tvApp == DOWNLOADER || received() }),
            Sub("bar", "Cliquer sur la barre d'adresse",
                "Fermez d'abord les fenêtres d'accueil (OK). Puis, en mode Souris, placez le curseur sur la barre en haut et touchez « Clic ». Le clavier de la TV s'ouvre.",
                manualLabel = "Le clavier est ouvert",
                detect = { barOpen || received() }),
            Sub("paste", "Mettre l'adresse du téléphone",
                "Le clavier doit être ouvert sur la TV. Si la barre contient déjà du texte, appuyez d'abord sur « Clear » dans Downloader.",
                "Coller l'adresse", { pasteUrl() },
                detect = { urlSet || received() }),
            Sub("go", "Lancer le téléchargement",
                "Valide l'adresse sur la TV. Si Downloader affiche une page, cliquez sur « Download ».",
                "Valider sur la TV", { tv?.submitField() },
                detect = { st() == ApkServer.State.DOWNLOADING || received() }),
            Sub("recv", "Fichier reçu par la TV",
                "Le téléphone envoie l'appli à la TV (quelques secondes).",
                manualLabel = "La TV propose d'installer",
                detect = { received() })
        )),
        Step("Installer l'appli Box", listOf(
            Sub("allow", "Autoriser Downloader (si demandé)",
                "Si la TV affiche « Paramètres » : ouvrez-les, activez « Downloader », puis faites ↩ Retour.",
                manualLabel = "Fait / pas demandé",
                detect = { tvApp == BOX || boxUp }),
            Sub("install", "Installer, puis ouvrir",
                "Sur la TV, choisissez « Installer », puis « Ouvrir ».",
                manualLabel = "L'appli Box est ouverte",
                detect = { tvApp == BOX || boxUp })
        )),
        Step("Activer la protection", listOf(
            Sub("openbox", "Ouvrir l'appli Box sur la TV",
                "Revenez à l'accueil de la TV, allez dans « Applications », puis ouvrez « Télécommande Box » avec OK. Cette ligne se coche toute seule.",
                "Aller à l'accueil de la TV", { tv?.send(RemoteKey.HOME) },
                manualLabel = "L'appli Box est ouverte",
                detect = { tvApp == BOX || boxUp }),
            Sub("protect", "Activer la protection",
                "Sur la TV : « Activer », choisissez « Télécommande Box » puis activez-la.",
                manualLabel = "C'est activé",
                detect = { boxUp })
        )),
        Step("Relier au téléphone", listOf(
            Sub("pair", "Afficher le code sur la TV et relier",
                "Un code s'affiche sur la TV, le téléphone le vérifie.",
                "Relier", {
                    withContext(Dispatchers.Main) {
                        BoxPairing.start(this@InstallBoxActivity, lifecycleScope, device) {
                            startActivity(Intent(this@InstallBoxActivity, BoxDashboardActivity::class.java).putExtra(BoxDashboardActivity.EXTRA_ID, device.id)); finish()
                        }
                    }
                })
        ))
    )

    private fun subDone(step: Step, i: Int): Boolean {
        // Une ligne plus bas détectée comme faite valide aussi celles du dessus
        // « Envoyer à Downloader » ne se coche que par lui-même (Downloader déjà ouvert ne suffit pas)
        if (step.subs[i].id == "direct") return step.subs[i].id in manual || step.subs[i].detect()
        return (i until step.subs.size).any { j -> step.subs[j].id in manual || step.subs[j].detect() }
    }

    private fun stepDone(i: Int): Boolean {
        if (i <= 2 && (tvApp == BOX || boxUp)) return true      // appli Box déjà là
        if (i <= 1 && received()) return true                   // fichier déjà envoyé
        val s = steps[i]
        return s.subs.indices.all { subDone(s, it) }
    }

    private fun currentStepIndex(): Int = steps.indices.firstOrNull { !stepDone(it) } ?: steps.size

    private fun currentSubIndex(step: Step): Int = step.subs.indices.firstOrNull { !subDone(step, it) } ?: step.subs.size

    // ---------------- Actions (uniquement sur appui) ----------------

    private fun runAction(sub: Sub) {
        val a = sub.action ?: return
        if (busy != null) return
        if (!tvConnected && sub.id != "pair") { note = "La TV n'est pas joignable. Vérifiez qu'elle est allumée et sur le même Wi-Fi."; render(); return }
        busy = sub.id; note = ""; render()
        DiagLog.log("ASSISTANT", "Appui « ${sub.actionLabel} » (ligne ${sub.id}) · appli TV : ${tvApp.ifEmpty { "?" }}")
        lifecycleScope.launch {
            runCatching { a() }.onFailure { note = "La TV n'a pas répondu. Réessayez." }
            delay(600)
            busy = null; render()
        }
    }

    /** Adresse sans « http:// » : Downloader l'ajoute lui-même, et c'est moins de touches à taper. */
    private fun shortUrl() = server.url.removePrefix("http://")

    private var askTyped = false
    private var typed = 0

    /** 1er essai : saisie directe (la box remplace tout le champ). Sans confirmation, on demande à l'utilisateur. */
    private suspend fun pasteUrl() {
        val t = tv ?: return
        t.sendText(shortUrl())
        urlSet = kotlinx.coroutines.withTimeoutOrNull(1_500) { t.fieldText.first { it == shortUrl() } } != null
        DiagLog.log("ASSISTANT", "Saisie directe ${if (urlSet) "confirmée par la TV" else "sans confirmation de la TV"}")
        if (!urlSet) askTyped = true
    }

    /** 2e essai, sur demande : on tape l'adresse touche par touche, comme un clavier. */
    private fun typeUrl() {
        val t = tv ?: return
        if (busy != null) return
        askTyped = false; busy = "paste"; typed = 0; render()
        lifecycleScope.launch {
            runCatching { t.typeKeys(shortUrl()) { n -> typed = n; runOnUiThread { render() } } }
                .onFailure { note = "La TV n'a pas répondu. Réessayez." }
            busy = null; typed = 0; render()
        }
    }

    // ---------------- Affichage ----------------

    private fun render() {
        val c = this
        content.removeAllViews()
        val cur = currentStepIndex()

        content.addView(Ui.text(c, device.name, 13f, muted = true))
        content.addView(Ui.title(c, "Installer l'appli Box"))
        content.addView(Ui.text(c, if (cur >= steps.size) "Terminé" else "Étape ${cur + 1} sur ${steps.size}", 14f, muted = true), Ui.lp(c, 4))
        content.addView(Ui.progress(c, cur.toFloat() / steps.size, false), LinearLayout.LayoutParams(-1, Ui.dp(c, 6)).apply { topMargin = Ui.dp(c, 8) })

        if (!ApkServer.bundled(c)) { content.addView(Ui.text(c, "Cette version ne contient pas l'appli Box.", 15f), Ui.lp(c, 12)); renderRemote(false); return }
        if (st() == ApkServer.State.ERROR) {
            content.addView(Ui.text(c, "Connectez le téléphone au Wi-Fi de la maison.", 15f).apply { setTextColor(Ui.color(c, R.color.danger)) }, Ui.lp(c, 12))
        }
        content.addView(statusPill(), Ui.lp(c, 12).apply { width = -2 })

        steps.forEachIndexed { i, s ->
            val done = i < cur || stepDone(i)
            val active = i == cur
            content.addView(if (active) activeTile(i, s) else compactTile(i, s, done), Ui.lp(c, 10))
        }
        content.addView(Ui.button(c, "✦  Analyser le problème avec Claude", primary = true) { DiagLog.export(this@InstallBoxActivity, toClaude = true) },
            LinearLayout.LayoutParams(-1, Ui.dp(c, 50)).apply { topMargin = Ui.dp(c, 18) })
        content.addView(TextView(c).apply {
            text = "Ou envoyer le journal de diagnostic"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Ui.color(c, R.color.accent)); setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 12))
            setOnClickListener { DiagLog.export(this@InstallBoxActivity) }
        }, Ui.lp(c, 8))
        renderRemote(cur < steps.size && cur != steps.size - 1)
    }

    private fun statusPill(): View {
        val c = this
        val ok = tvConnected
        return TextView(c).apply {
            text = if (ok) "●  TV connectée" + (appName()?.let { " · $it" } ?: "") else "●  Connexion à la TV…"
            textSize = 13f; setTextColor(Ui.color(c, if (ok) R.color.ok else R.color.muted))
            background = GradientDrawable().apply { setColor(Ui.color(c, R.color.surface)); cornerRadius = 99f * resources.displayMetrics.density }
            setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6))
        }
    }

    private fun appName(): String? = when (tvApp) {
        DOWNLOADER -> "Downloader ouvert"
        BOX -> "Appli Box ouverte"
        in STORE -> "Play Store ouvert"
        "" -> null
        else -> null
    }

    private fun tileBg(active: Boolean) = GradientDrawable().apply {
        setColor(Ui.color(this@InstallBoxActivity, R.color.surface)); cornerRadius = 22 * resources.displayMetrics.density
        if (active) setStroke(Ui.dp(this@InstallBoxActivity, 2), Ui.color(this@InstallBoxActivity, R.color.accent))
    }

    private fun compactTile(i: Int, s: Step, done: Boolean): View {
        val c = this
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = tileBg(false)
            setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14))
            alpha = if (done) 1f else 0.55f
            addView(badge("${i + 1}", if (done) Badge.DONE else Badge.IDLE, 30))
            addView(TextView(c).apply { text = s.title; textSize = 17f; setTextColor(Ui.color(c, R.color.ink)) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(c, 12) })
            if (done) addView(TextView(c).apply { text = "Fait"; textSize = 13f; setTextColor(Ui.color(c, R.color.ok)); setTypeface(typeface, Typeface.BOLD) })
        }
    }

    private fun activeTile(i: Int, s: Step): View {
        val c = this
        val tile = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = tileBg(true)
            setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16))
        }
        tile.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(badge("${i + 1}", Badge.ACTIVE, 34))
            addView(TextView(c).apply { text = s.title; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.color(c, R.color.ink)) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(c, 12) })
        })

        val curSub = currentSubIndex(s)
        s.subs.forEachIndexed { k, sub ->
            val done = subDone(s, k)
            val isCur = k == curSub
            tile.addView(subRow(k, sub, done, isCur, last = k == s.subs.lastIndex), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(c, if (k == 0) 16 else 0) })
        }
        return tile
    }

    /** Ligne d'une étape : pastille + trait vertical (frise), titre, et pour la ligne en cours son explication et ses boutons. */
    private fun subRow(k: Int, sub: Sub, done: Boolean, isCur: Boolean, last: Boolean): View {
        val c = this
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }

        val rail = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        rail.addView(badge(if (done) "" else "${k + 1}", when { done -> Badge.DONE; isCur -> Badge.ACTIVE; else -> Badge.IDLE }, 24,
            spinning = isCur && (busy == sub.id || (sub.id == "recv" && st() == ApkServer.State.DOWNLOADING))))
        if (!last) rail.addView(View(c).apply { setBackgroundColor(Ui.color(c, if (done) R.color.ok else R.color.line)) },
            LinearLayout.LayoutParams(Ui.dp(c, 2), 0, 1f).apply { topMargin = Ui.dp(c, 4); bottomMargin = Ui.dp(c, 4) })
        row.addView(rail, LinearLayout.LayoutParams(Ui.dp(c, 24), -1))

        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 12), Ui.dp(c, 1), 0, Ui.dp(c, if (last) 0 else 14)) }
        body.addView(TextView(c).apply {
            text = sub.label; textSize = if (isCur) 16f else 15f
            setTextColor(Ui.color(c, when { done -> R.color.ok; isCur -> R.color.ink; else -> R.color.muted }))
            if (isCur) setTypeface(typeface, Typeface.BOLD)
        })
        if (isCur) {
            body.addView(Ui.text(c, sub.help, 14f, muted = true), Ui.lp(c, 4))
            if (sub.id == "paste" || sub.id == "go") {
                body.addView(urlCard(), Ui.lp(c, 10))
                body.addView(Ui.text(c, when (serverOk) {
                    true -> "✓ Le téléphone est prêt à envoyer l'appli"
                    false -> "✗ Le téléphone ne répond pas sur cette adresse : vérifiez qu'il est sur le Wi-Fi de la maison, puis rouvrez l'assistant."
                    null -> "Vérification du téléphone…"
                }, 13f).apply { setTextColor(Ui.color(c, when (serverOk) { true -> R.color.ok; false -> R.color.danger; null -> R.color.muted })) }, Ui.lp(c, 6))
            }
            if (note.isNotEmpty()) body.addView(Ui.text(c, note, 14f).apply { setTextColor(Ui.color(c, R.color.danger)) }, Ui.lp(c, 8))
            val buttons = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            if (sub.id == "paste" && busy == "paste" && typed > 0) {
                body.addView(Ui.text(c, "Saisie sur la TV : $typed / ${shortUrl().length} caractères…", 14f), Ui.lp(c, 10))
                body.addView(Ui.progress(c, typed.toFloat() / shortUrl().length, false), LinearLayout.LayoutParams(-1, Ui.dp(c, 6)).apply { topMargin = Ui.dp(c, 6) })
                return row.apply { addView(body, LinearLayout.LayoutParams(0, -2, 1f)) }
            }
            if (sub.id == "paste" && askTyped) {
                body.addView(Ui.text(c, "L'adresse est-elle apparue dans la barre, sur la TV ?", 15f).apply { setTypeface(typeface, Typeface.BOLD) }, Ui.lp(c, 12))
                buttons.addView(Ui.button(c, "Oui, elle est affichée", primary = true) { askTyped = false; manual += "paste"; DiagLog.log("ASSISTANT", "Adresse visible sur la TV (confirmé)"); render() },
                    LinearLayout.LayoutParams(-1, Ui.dp(c, 50)))
                buttons.addView(Ui.button(c, "Non, la taper touche par touche") { DiagLog.log("ASSISTANT", "Saisie touche par touche demandée"); typeUrl() },
                    LinearLayout.LayoutParams(-1, Ui.dp(c, 50)).apply { topMargin = Ui.dp(c, 8) })
                body.addView(buttons, Ui.lp(c, 10))
                return row.apply { addView(body, LinearLayout.LayoutParams(0, -2, 1f)) }
            }
            sub.actionLabel?.let { l ->
                buttons.addView(Ui.button(c, if (busy == sub.id) "Envoi à la TV…" else l, primary = true) { runAction(sub) }.apply { alpha = if (busy != null) 0.6f else 1f },
                    LinearLayout.LayoutParams(-1, Ui.dp(c, 50)))
            }
            if (sub.id != "pair") buttons.addView(Ui.button(c, sub.manualLabel, primary = sub.actionLabel == null) { manual += sub.id; note = ""; DiagLog.log("ASSISTANT", "Validé à la main : ${sub.id}"); render() },
                LinearLayout.LayoutParams(-1, Ui.dp(c, 50)).apply { topMargin = Ui.dp(c, if (sub.actionLabel != null) 8 else 0) })
            body.addView(buttons, Ui.lp(c, 12))
        }
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun urlCard(): View {
        val c = this
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Ui.color(c, R.color.bg)); cornerRadius = 12 * resources.displayMetrics.density; setStroke(Ui.dp(c, 1), Ui.color(c, R.color.line)) }
            setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8))
            addView(TextView(c).apply {
                text = shortUrl().ifEmpty { "Préparation…" }; textSize = 14f; typeface = Typeface.MONOSPACE
                setTextColor(Ui.color(c, R.color.ink)); setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(c).apply {
                text = "Copier"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.color(c, R.color.accent))
                setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8))
                setOnClickListener {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", shortUrl()))
                    Ui.toast(c, "Adresse copiée")
                }
            })
        }
    }

    private enum class Badge { DONE, ACTIVE, IDLE }

    private fun badge(label: String, kind: Badge, sizeDp: Int, spinning: Boolean = false): View {
        val c = this
        val size = Ui.dp(c, sizeDp)
        if (spinning) return ProgressBar(c).apply { layoutParams = LinearLayout.LayoutParams(size, size) }
        return TextView(c).apply {
            text = if (kind == Badge.DONE) "✓" else label
            gravity = Gravity.CENTER; textSize = sizeDp * 0.45f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (kind == Badge.IDLE) Ui.color(c, R.color.muted) else Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.color(c, when (kind) { Badge.DONE -> R.color.ok; Badge.ACTIVE -> R.color.accent; Badge.IDLE -> R.color.surface2 }))
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    // ---------------- Télécommande fixée en bas ----------------

    private fun renderRemote(show: Boolean) {
        val bt = fr.paul.telecommande.core.BtMouse.state.value == fr.paul.telecommande.core.BtMouse.State.CONNECTED
        val key = if (!show) "none" else if (mouse) "mouse$bt" else "dpad"
        if (key == builtRemote) return          // ne pas reconstruire pendant un glissé du doigt
        builtRemote = key
        remoteHost.removeAllViews()
        if (!show) return
        val c = this
        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.color(c, R.color.surface))
                val r = 26 * resources.displayMetrics.density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 16))
        }
        // En-tête : titre + bascule Flèches / Souris
        panel.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(c).apply { text = "Télécommande"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.color(c, R.color.ink)) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(segmented())
        })

        if (mouse) {
            val pad = TouchpadView(c).apply {
                mouseMode = true
                listener = { g -> if (g == TouchpadView.Gesture.TAP) { if (bt) fr.paul.telecommande.core.BtMouse.click(1) else press(RemoteKey.OK) } }
                if (bt) {
                    rawMoveListener = { dx, dy -> fr.paul.telecommande.core.BtMouse.move(dx, dy) }
                    scrollListener = { n -> fr.paul.telecommande.core.BtMouse.scroll(-n) }
                } else {
                    stepListener = { g -> tv?.let { MouseHold.step(lifecycleScope, it, g) } }
                    releaseListener = { MouseHold.release() }
                }
            }
            if (!bt) panel.addView(TextView(c).apply {
                text = "Pas de flèche visible sur la TV ? Utiliser la vraie souris (Bluetooth) ›"
                textSize = 13f; setTextColor(Ui.color(c, R.color.accent)); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 2))
                setOnClickListener { startActivity(Intent(c, BtMouseActivity::class.java)) }
            })
            panel.addView(pad, LinearLayout.LayoutParams(-1, Ui.dp(c, 220)).apply { topMargin = Ui.dp(c, 10) })
            panel.addView(LinearLayout(c).apply {
                addView(Ui.button(c, "↩  Retour") { press(RemoteKey.BACK) }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f).apply { marginEnd = Ui.dp(c, 8) })
                addView(Ui.button(c, "Clic", primary = true) { if (bt) fr.paul.telecommande.core.BtMouse.click(1) else press(RemoteKey.OK) }, LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1.4f))
            }, Ui.lp(c, 10))
        } else {
            panel.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER
                addView(sideKey("↩", "Retour") { press(RemoteKey.BACK) })
                addView(dpad(), LinearLayout.LayoutParams(-2, -2).apply { setMargins(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0) })
                addView(sideKey("⌂", "Accueil") { press(RemoteKey.HOME) })
            }, Ui.lp(c, 8))
        }
        remoteHost.addView(panel)
    }

    private fun segmented(): View {
        val c = this
        val box = LinearLayout(c).apply {
            background = GradientDrawable().apply { setColor(Ui.color(c, R.color.surface2)); cornerRadius = 99f * resources.displayMetrics.density }
            setPadding(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3))
        }
        listOf("Flèches" to false, "Souris" to true).forEach { (l, m) ->
            val on = mouse == m
            box.addView(TextView(c).apply {
                text = l; textSize = 13f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setTextColor(Ui.color(c, if (on) R.color.ink else R.color.muted))
                background = if (on) GradientDrawable().apply { setColor(Ui.color(c, R.color.bg)); cornerRadius = 99f * resources.displayMetrics.density } else null
                setPadding(Ui.dp(c, 14), Ui.dp(c, 6), Ui.dp(c, 14), Ui.dp(c, 6))
                setOnClickListener { userPickedMode = true; mouse = m; MouseHold.stopAll(); renderRemote(true) }
            })
        }
        return box
    }

    /** Croix directionnelle ronde, OK au centre. */
    private fun dpad(): View {
        val c = this
        val s = Ui.dp(c, 168)
        val ring = FrameLayout(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.color(c, R.color.bg)); setStroke(Ui.dp(c, 1), Ui.color(c, R.color.line)) }
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        val arrow = Ui.dp(c, 56)
        fun arrowKey(label: String, g: Int, k: RemoteKey) = TextView(c).apply {
            text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Ui.color(c, R.color.ink))
            setBackgroundResource(R.drawable.bg_round); isClickable = true
            setOnClickListener { press(k) }
            layoutParams = FrameLayout.LayoutParams(arrow, arrow, g)
        }
        ring.addView(arrowKey("▲", Gravity.TOP or Gravity.CENTER_HORIZONTAL, RemoteKey.UP))
        ring.addView(arrowKey("▼", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, RemoteKey.DOWN))
        ring.addView(arrowKey("◀", Gravity.START or Gravity.CENTER_VERTICAL, RemoteKey.LEFT))
        ring.addView(arrowKey("▶", Gravity.END or Gravity.CENTER_VERTICAL, RemoteKey.RIGHT))
        val ok = Ui.dp(c, 64)
        ring.addView(TextView(c).apply {
            text = "OK"; textSize = 17f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.color(c, R.color.accent)) }
            isClickable = true; setOnClickListener { press(RemoteKey.OK) }
            layoutParams = FrameLayout.LayoutParams(ok, ok, Gravity.CENTER)
        })
        return ring
    }

    private fun sideKey(icon: String, label: String, action: () -> Unit): View {
        val c = this
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true; setOnClickListener { action() }
            addView(TextView(c).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Ui.color(c, R.color.ink))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.color(c, R.color.bg)); setStroke(Ui.dp(c, 1), Ui.color(c, R.color.line)) }
            }, LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)))
            addView(Ui.text(c, label, 12f, muted = true).apply { gravity = Gravity.CENTER }, Ui.lp(c, 4))
        }
    }

    private fun press(k: RemoteKey) {
        val t = tv ?: return
        DiagLog.log("ASSISTANT", "Touche $k · appli TV : ${tvApp.ifEmpty { "?" }}")
        lifecycleScope.launch { runCatching { t.connect(this@InstallBoxActivity); t.send(k) } }
    }

    // Appairage télécommande Android TV si nécessaire
    override suspend fun askCode(title: String, message: String, hint: String): String? = withContext(Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val input = android.widget.EditText(this@InstallBoxActivity).apply { this.hint = hint }
            androidx.appcompat.app.AlertDialog.Builder(this@InstallBoxActivity).setTitle(title).setMessage(message).setView(input)
                .setPositiveButton("Valider") { _, _ -> if (cont.isActive) cont.resume(input.text.toString()) {} }
                .setNegativeButton("Annuler") { _, _ -> if (cont.isActive) cont.resume(null) {} }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) {} }
                .show()
        }
    }

    override fun info(message: String) { runOnUiThread { note = message; render() } }
}

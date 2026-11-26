package fr.paul.telecommande.ui

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.Device
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DeviceType
import fr.paul.telecommande.core.Discovery
import fr.paul.telecommande.core.Found
import fr.paul.telecommande.drivers.IrBrands
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object { const val EXTRA_ADD = "add" }

    private lateinit var store: DeviceStore
    private lateinit var list: LinearLayout
    /** Dernier état connu de chaque TV équipée de l'appli Box (null = injoignable). */
    private val status = HashMap<String, org.json.JSONObject?>()
    private var loading: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.systemBars(this)
        store = DeviceStore(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), 0) }
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        setContentView(AuroraNav.wrap(this, scroll, AuroraNav.Tab.HOME))
        // Relance le suivi / l'assistant s'ils étaient actifs (après une mise à jour par exemple)
        val parental = fr.paul.telecommande.core.ParentalStore(this)
        if (store.all().any { it.isSmartBox && (it.hasBoxApp || it.monitorEnabled || parental.rules(it.id).enabled ||
                (it.ytScreenId.isNotBlank() && (it.ytAutoSkipAds || it.ytSkipSponsors))) }) {
            fr.paul.telecommande.service.AssistantService.start(this)
        }
        if (intent.getBooleanExtra(EXTRA_ADD, false)) showAddMenu()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ADD, false)) showAddMenu()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        loadStatus()
        PhoneUpdate.checkAndOffer(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = Ui.color(this, id)

    /** Interroge les TV équipées de l'appli Box (en parallèle), puis redessine. */
    private fun loadStatus() {
        loading?.cancel()
        loading = lifecycleScope.launch {
            store.all().filter { it.hasBoxApp }.map { d ->
                d to async { runCatching { fr.paul.telecommande.core.BoxClient.of(this@MainActivity, d).call("status") }.getOrNull() }
            }.forEach { (d, job) -> status[d.id] = job.await() }
            refresh()
        }
    }

    private fun refresh() {
        list.removeAllViews()
        val devices = store.all()
        list.addView(header())
        if (devices.isEmpty()) {
            list.addView(welcomeCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            list.addView(AuroraNav.spacer(this))
            return
        }
        val main = AuroraNav.primary(this) ?: devices.first()
        list.addView(heroCard(main, devices.size > 1), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        if (main.isSmartBox) list.addView(tiles(main), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        pendingCards(main).forEach { list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }

        // Les autres écrans de la maison
        val head = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "Mes écrans"; textSize = 18f; typeface = Ui.bold; setTextColor(col(R.color.ink)) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = "+ Ajouter"; textSize = 14f; typeface = Ui.medium; setTextColor(col(R.color.accent))
            setPadding(dp(12), dp(10), dp(4), dp(10)); isClickable = true; isFocusable = true
            setOnClickListener { showAddMenu() }
        })
        list.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        devices.forEach { d -> list.addView(deviceRow(d, d.id == main.id), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        list.addView(AuroraNav.spacer(this))
    }

    private fun header(): View {
        val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        texts.addView(TextView(this).apply {
            text = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.FRANCE).format(java.util.Date()).replaceFirstChar { it.uppercase() }
            textSize = 13f; setTextColor(col(R.color.muted))
        })
        texts.addView(TextView(this).apply {
            text = if (hour >= 18 || hour < 5) "Bonsoir" else "Bonjour"
            textSize = 30f; typeface = Ui.bold; letterSpacing = -0.02f; setTextColor(col(R.color.ink)); includeFontPadding = false
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(AuroraNav.roundButton(this, R.drawable.ic_settings, "Réglages") { AuroraNav.settings(this) })
        return row
    }

    /** Aucun appareil : grande carte d'accueil avec un seul bouton. */
    private fun welcomeCard(): View {
        val card = heroBase()
        card.addView(TextView(this).apply { text = "Bienvenue"; textSize = 26f; typeface = Ui.bold; setTextColor(Color.WHITE) })
        card.addView(TextView(this).apply {
            text = "Ajoutez votre TV ou votre box : l'appli la trouve toute seule sur le Wi-Fi. Vous aurez la télécommande et le contrôle parental."
            textSize = 14.5f; setTextColor(HERO_MUTED); setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        card.addView(heroButton("Ajouter ma TV") { showAddMenu() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })
        return card
    }

    private val HERO_MUTED = 0xFFDCDDF5.toInt()

    private fun heroBase() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_hero)
        elevation = dp(10).toFloat()
        setPadding(dp(20), dp(18), dp(20), dp(20))
    }

    /** Bouton blanc en pilule posé sur la carte foncée. */
    private fun heroButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; gravity = android.view.Gravity.CENTER; textSize = 15f; typeface = Ui.medium
        setTextColor(col(R.color.hero))
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000),
            android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(99).toFloat() }, null)
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }

    /** Bouton rond translucide posé sur la carte foncée. */
    private fun heroRound(icon: Int, desc: String, action: () -> Unit) = android.widget.ImageButton(this).apply {
        setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x26FFFFFF); setStroke(dp(1), 0x40FFFFFF) }, null)
        contentDescription = desc
        setOnClickListener { action() }
    }

    /**
     * Carte foncée « en direct » de la TV principale : ce qui passe, qui regarde,
     * et les gestes du moment (télécommande, applis, message).
     */
    private fun heroCard(d: Device, canSwitch: Boolean): View {
        val st = if (d.hasBoxApp) status[d.id] else null
        val known = status.containsKey(d.id)
        val on = st?.optBoolean("interactive") == true
        val card = heroBase()

        val top = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val dotColor = when { !d.hasBoxApp -> col(R.color.ok); on -> col(R.color.ok); else -> 0xFF8B8DB0.toInt() }
        top.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(dotColor) }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        val state = when {
            !d.hasBoxApp -> d.name
            !known -> d.name + " · connexion…"
            st == null -> d.name + " · injoignable"
            on -> d.name + " · en direct"
            else -> d.name + " · en veille"
        }
        top.addView(TextView(this).apply {
            text = state + if (canSwitch) "  ▾" else ""; textSize = 13.5f; setTextColor(HERO_MUTED); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (canSwitch) { isClickable = true; isFocusable = true; contentDescription = "Changer de TV principale, actuellement ${d.name}"; setOnClickListener { pickPrimary() } }
            setPadding(0, dp(6), 0, dp(6))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        st?.optLong("todayTotal")?.takeIf { it > 0 }?.let {
            top.addView(TextView(this).apply { text = fr.paul.telecommande.core.Usage.format(it) + " aujourd'hui"; textSize = 13f; setTextColor(HERO_MUTED) })
        }
        card.addView(top)

        val media = st?.optJSONObject("media")
        val title = when {
            !d.hasBoxApp -> when (d.type) { DeviceType.IR -> "Télécommande infrarouge"; else -> "Prête à piloter" }
            st == null -> if (known) "TV éteinte ou hors Wi-Fi" else "Connexion à la TV…"
            !on -> "TV en veille"
            media != null && media.optString("title").isNotBlank() -> media.optString("title")
            else -> st.optString("label").ifBlank { "Accueil" }
        }
        val who = st?.optJSONObject("profile")
        val sub = when {
            !d.hasBoxApp -> when (d.type) {
                DeviceType.IR -> "Infrarouge · " + (IrBrands.byId(d.irBrand)?.label ?: "Toutes marques")
                else -> d.type.label.substringBefore(" (")
            }
            st == null && known -> "Vérifiez qu'elle est allumée et sur le même Wi-Fi."
            st == null -> "Un instant…"
            !on -> "Allumez-la depuis la télécommande (appui long sur la touche marche)."
            else -> listOfNotNull(
                media?.optString("subtitle")?.takeIf { it.isNotBlank() },
                st.optString("label").takeIf { media != null && it.isNotBlank() },
                who?.let { "regardé par ${it.optString("emoji")} ${it.optString("name")}".trim() }
            ).joinToString(" · ").ifBlank { "Rien en lecture" }
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 23f; typeface = Ui.bold; setTextColor(Color.WHITE); letterSpacing = -0.01f
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        card.addView(TextView(this).apply {
            text = sub; textSize = 14f; setTextColor(HERO_MUTED); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val actions = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        actions.addView(heroButton("Télécommande") { openRemote(d) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        if (d.hasBoxApp) {
            actions.addView(heroRound(R.drawable.ic_apps, "Applis de la TV") { BoxActions.appsSheet(this, d) },
                LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginStart = dp(10) })
            actions.addView(heroRound(R.drawable.ic_message, "Envoyer un message sur la TV") { BoxActions.message(this, d) },
                LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginStart = dp(10) })
        }
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        card.isClickable = true; card.setOnClickListener { openRemote(d) }
        return card
    }

    private fun pickPrimary() {
        val all = store.all()
        val main = AuroraNav.primary(this)
        AlertDialog.Builder(this).setTitle("TV principale")
            .setSingleChoiceItems(all.map { it.name }.toTypedArray(), all.indexOfFirst { it.id == main?.id }) { dlg, i ->
                AuroraNav.setPrimary(this, all[i]); dlg.dismiss(); refresh()
            }.setNegativeButton("Annuler", null).show()
    }

    /** Deux tuiles : temps de TV du jour et couvre-feu / protection. */
    private fun tiles(d: Device): View {
        val parental = fr.paul.telecommande.core.ParentalStore(this)
        val box = if (d.hasBoxApp) parental.boxRules(d.id) else null
        val phone = parental.rules(d.id)
        val enabled = box?.enabled ?: phone.enabled
        // Les limites de temps sont propres au profil qui regarde (« Qui regarde ? » sur la TV)
        val watcherId = status[d.id]?.optJSONObject("profile")?.optString("id")
        val watcher = box?.profiles?.firstOrNull { it.id == watcherId }
        val limit = if (!enabled || watcher?.unlimited == true) 0 else box?.forProfile(watcher)?.dailyTotalMin ?: phone.dailyTotalMin
        val tracking = d.hasBoxApp || d.monitorEnabled || phone.enabled
        val today: Long? = when {
            d.hasBoxApp -> status[d.id]?.optLong("todayTotal")
            tracking -> fr.paul.telecommande.core.TvMonitor.todayUsage(this, d.id).values.sum()
            else -> null
        }
        val bedtimeOn = enabled && (box?.bedtimeEnabled ?: phone.bedtimeEnabled)
        val bedtime = box?.bedtimeStart ?: phone.bedtimeStart

        val row = LinearLayout(this)
        val t1 = tile("Temps de TV", today?.let { fr.paul.telecommande.core.Usage.format(it) } ?: "—")
        if (today != null && limit > 0) {
            val over = today >= limit * 60_000L
            t1.addView(Ui.progress(this, today.toFloat() / (limit * 60_000L), over), LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })
            t1.addView(small("sur " + fr.paul.telecommande.core.Usage.format(limit * 60_000L) + (watcher?.let { " · ${it.name}" } ?: " aujourd'hui")), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        } else t1.addView(small(when { today == null && !tracking -> "Suivi désactivé"; today == null -> "TV injoignable"; else -> "aujourd'hui · sans limite" }),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        t1.setOnClickListener { AuroraNav.open(this, AuroraNav.Tab.ACTIVITY) }
        t1.contentDescription = "Temps de TV aujourd'hui, voir l'activité"

        val t2 = tile(if (bedtimeOn) "Couvre-feu" else "Protection", if (bedtimeOn) hm(bedtime) else if (enabled) "Active" else "Inactive")
        t2.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_shield)
                imageTintList = android.content.res.ColorStateList.valueOf(col(if (enabled) R.color.accent2 else R.color.muted))
            }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) })
            addView(TextView(this@MainActivity).apply {
                text = if (enabled) "Protection active" else "Toucher pour régler"
                textSize = 12f; typeface = Ui.medium; setTextColor(col(if (enabled) R.color.accent2 else R.color.muted)); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        t2.setOnClickListener { AuroraNav.open(this, AuroraNav.Tab.PARENTAL) }
        t2.contentDescription = "Contrôle parental, " + if (enabled) "actif" else "inactif"

        row.addView(t1, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(6) })
        row.addView(t2, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(6) })
        return row
    }

    private fun tile(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card); elevation = dp(2).toFloat()
        setPadding(dp(16), dp(16), dp(16), dp(16))
        isClickable = true; isFocusable = true
        addView(TextView(this@MainActivity).apply { text = label; textSize = 13f; setTextColor(col(R.color.muted)) })
        addView(TextView(this@MainActivity).apply { text = value; textSize = 24f; typeface = Ui.bold; setTextColor(col(R.color.ink)); maxLines = 1 },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
    }

    private fun small(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(col(R.color.muted)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }

    private fun hm(m: Int) = if (m % 60 == 0) "${m / 60} h" else "${m / 60} h %02d".format(m % 60)

    /** Demandes de temps en attente, envoyées depuis la TV : réponse en un geste. */
    private fun pendingCards(d: Device): List<View> {
        val arr = status[d.id]?.optJSONArray("pending") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val card = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply { setColor(col(R.color.notice_bg)); cornerRadius = dp(26).toFloat() }
                setPadding(dp(18), dp(14), dp(12), dp(14))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = "+${r.optInt("minutes")} min demandées"; textSize = 15f; typeface = Ui.bold; setTextColor(col(R.color.ink)) })
            texts.addView(TextView(this).apply { text = listOf(r.optString("label"), d.name).filter { it.isNotBlank() }.joinToString(" · "); textSize = 13f; setTextColor(col(R.color.notice_ink)) })
            card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            fun answer(accept: Boolean) = lifecycleScope.launch {
                fr.paul.telecommande.core.BoxLink.respond(this@MainActivity, d, r.optString("id"), accept, r.optInt("minutes", 15))
                    .onSuccess { Ui.toast(this@MainActivity, if (accept) "Temps accordé" else "Demande refusée") }
                    .onFailure { Ui.toast(this@MainActivity, "Erreur : ${it.message}") }
                loadStatus()
            }
            card.addView(android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_close); imageTintList = android.content.res.ColorStateList.valueOf(col(R.color.ink))
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(col(R.color.surface)) }
                contentDescription = "Refuser"; setOnClickListener { answer(false) }
            }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(8) })
            card.addView(android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_ok); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(col(R.color.primary)) }
                contentDescription = "Accepter"; setOnClickListener { answer(true) }
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            card
        }
    }

    /** Ligne d'appareil : pastille en dégradé, nom, état, flèche. */
    private fun deviceRow(d: Device, isMain: Boolean): View {
        val card = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card); elevation = dp(2).toFloat()
            setPadding(dp(14), dp(12), dp(12), dp(12))
            isClickable = true; isFocusable = true
            setOnClickListener { openRemote(d) }
            setOnLongClickListener { showDeviceMenu(d); true }
            contentDescription = "${d.name}, ouvrir la télécommande. Appui long : options"
        }
        card.addView(TextView(this).apply {
            text = when (d.type) { DeviceType.IR -> "IR"; DeviceType.ANDROID_TV -> "TV"; else -> d.type.label.take(2).uppercase() }
            gravity = android.view.Gravity.CENTER; textSize = 14f; typeface = Ui.bold; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(col(R.color.brand_a), col(R.color.brand_b))).apply { cornerRadius = dp(15).toFloat() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = d.name; textSize = 16f; typeface = Ui.bold; setTextColor(col(R.color.ink)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val st = status[d.id]
        val detail = when {
            d.hasBoxApp && status.containsKey(d.id) -> when {
                st == null -> "Injoignable"
                st.optBoolean("interactive") -> "Allumée · " + st.optString("label").ifBlank { "accueil" }
                else -> "En veille"
            } + if (isMain) " · TV principale" else ""
            d.type == DeviceType.IR -> "Infrarouge · " + (IrBrands.byId(d.irBrand)?.label ?: "Toutes marques")
            else -> d.type.label.substringBefore(" (") + if (isMain) " · TV principale" else ""
        }
        texts.addView(TextView(this).apply { text = detail; textSize = 13f; setTextColor(col(R.color.muted)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
        card.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(col(R.color.ink))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(col(R.color.surface2)) }
            val p = dp(9); setPadding(p, p, p, p)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        return card
    }

    private fun openRemote(d: Device) {
        AuroraNav.setPrimary(this, d)
        startActivity(Intent(this, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_ID, d.id))
    }

    private fun showAddMenu() {
        val options = arrayOf(getString(R.string.scan), getString(R.string.manual), "Infrarouge (ce téléphone)")
        AlertDialog.Builder(this).setTitle(R.string.add).setItems(options) { _, which ->
            when (which) {
                0 -> scan()
                1 -> editDevice(null, null)
                2 -> editDevice(null, Device(name = "TV (infrarouge)", type = DeviceType.IR, irBrand = IrBrands.AUTO))
            }
        }.show()
    }

    @Suppress("DEPRECATION")
    private fun scan() {
        val progress = ProgressDialog(this).apply {
            setMessage("Recherche des TV et box sur le Wi-Fi (10 secondes environ)…"); setCancelable(false); show()
        }
        lifecycleScope.launch {
            val found = runCatching { Discovery(this@MainActivity).scan() }.getOrDefault(emptyList())
            progress.dismiss()
            val known = store.all().map { it.host }.toSet()
            val fresh = found.filter { it.host !in known }
            if (fresh.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Aucun nouvel appareil")
                    .setMessage("Vérifiez que le téléphone est sur le même Wi-Fi que la TV et qu'elle est allumée. Vous pouvez aussi l'ajouter manuellement avec son adresse IP (Réglages réseau de la TV).")
                    .setPositiveButton(R.string.manual) { _, _ -> editDevice(null, null) }
                    .setNegativeButton("Fermer", null).show()
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Appareils trouvés")
                .setItems(fresh.map { "${it.name}\n${it.type.label.substringBefore(" (")}" }.toTypedArray()) { _, i ->
                    val f: Found = fresh[i]
                    editDevice(null, Device(name = f.name.substringBefore(" –").substringBefore(" ("), type = f.type, host = f.host))
                }.setNegativeButton("Fermer", null).show()
        }
    }

    private fun showDeviceMenu(d: Device) {
        val items = mutableListOf("Modifier", "Réinitialiser l'appairage", "Supprimer")
        if (d.isSmartBox) { items.add(0, "Contrôle parental"); items.add(0, "Temps d'écran") }
        if (d.type == DeviceType.VESTEL) items.add(0, "Tester la télécommande")
        AlertDialog.Builder(this).setTitle(d.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Tester la télécommande" -> VestelSetup.start(this, d) { refresh() }
                    "Temps d'écran" -> startActivity(Intent(this, StatsActivity::class.java).putExtra(StatsActivity.EXTRA_ID, d.id))
                    "Contrôle parental" -> startActivity(Intent(this, ParentalActivity::class.java).putExtra(ParentalActivity.EXTRA_ID, d.id))
                    "Modifier" -> Ui.requirePin(this, "Modifier « ${d.name} » ?") { editDevice(d, null) }
                    "Réinitialiser l'appairage" -> { d.token = ""; if (d.type == DeviceType.SONY || d.type == DeviceType.FREEBOX || d.type == DeviceType.VESTEL) d.secret = ""
                        store.save(d); Toast.makeText(this, "Appairage réinitialisé", Toast.LENGTH_SHORT).show() }
                    "Supprimer" -> Ui.requirePin(this, "Supprimer « ${d.name} » ?") {
                        AlertDialog.Builder(this).setMessage("Supprimer « ${d.name} » ?")
                            .setPositiveButton("Supprimer") { _, _ ->
                                store.delete(d.id)
                                fr.paul.telecommande.core.ParentalStore(this).save(d.id, fr.paul.telecommande.core.ParentalRules())
                                fr.paul.telecommande.core.TvMonitor.sync(this)
                                refresh()
                            }
                            .setNegativeButton("Annuler", null).show()
                    }
                }
            }.show()
    }

    /** Formulaire d'ajout / modification, construit dynamiquement selon le type choisi. */
    private fun editDevice(existing: Device?, preset: Device?) {
        val d = existing ?: preset ?: Device(name = "", type = DeviceType.SAMSUNG)
        val types = DeviceType.values()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 12f; alpha = 0.7f; setPadding(0, dp(10), 0, 0) }
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply { this.hint = hint; setText(value); inputType = type; setSingleLine() }

        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types.map { it.label })
            setSelection(types.indexOf(d.type))
        }
        val name = field("Nom (ex. TV du salon)", d.name)
        val hostLabel = label("Adresse IP")
        val host = field("ex. 192.168.1.20", d.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val portLabel = label("Port")
        val port = field("Port", d.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val macLabel = label("Adresse MAC (facultatif, pour allumer la TV)")
        val mac = field("AA:BB:CC:DD:EE:FF", d.mac)
        val brandLabel = label("Marque infrarouge")
        val brands = listOf(IrBrands.AUTO to "Toutes marques (essai automatique)") + IrBrands.ALL.map { it.id to it.label }
        val brandSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, brands.map { it.second })
            setSelection(brands.indexOfFirst { it.first == d.irBrand }.coerceAtLeast(0))
        }
        val userLabel = label("Kodi : identifiant / mot de passe (si activés)")
        val user = field("Identifiant", d.user)
        val pass = field("Mot de passe", if (d.type == DeviceType.KODI) d.secret else "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val help = TextView(this).apply { textSize = 12f; alpha = 0.8f; setPadding(0, dp(12), 0, dp(4)) }

        form.addView(label("Type d'appareil")); form.addView(typeSpinner)
        form.addView(label("Nom")); form.addView(name)
        listOf(hostLabel, host, portLabel, port, macLabel, mac, brandLabel, brandSpinner, userLabel, user, pass, help).forEach { form.addView(it) }

        fun update(t: DeviceType) {
            val net = t != DeviceType.IR
            listOf(hostLabel, host, macLabel, mac).forEach { it.visibility = if (net) View.VISIBLE else View.GONE }
            val custom = t == DeviceType.KODI || t == DeviceType.ROKU || t == DeviceType.PHILIPS
            portLabel.visibility = if (custom) View.VISIBLE else View.GONE
            port.visibility = portLabel.visibility
            listOf(brandLabel, brandSpinner).forEach { it.visibility = if (t == DeviceType.IR) View.VISIBLE else View.GONE }
            listOf(userLabel, user, pass).forEach { it.visibility = if (t == DeviceType.KODI) View.VISIBLE else View.GONE }
            if (t == DeviceType.FREEBOX && host.text.isBlank()) host.hint = "hd1.freebox.fr (par défaut)"
            help.text = helpFor(t)
        }
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val t = types[pos]
                if (port.text.toString() == d.type.defaultPort.toString() || port.text.isBlank()) port.setText(t.defaultPort.toString())
                update(t)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        update(d.type)

        val scroll = android.widget.ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nouvel appareil" else "Modifier")
            .setView(scroll)
            .setPositiveButton("Enregistrer") { _, _ ->
                val t = types[typeSpinner.selectedItemPosition]
                if (t.needsHost && t != DeviceType.FREEBOX && host.text.isBlank()) {
                    Toast.makeText(this, "Adresse IP obligatoire", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                if (t != d.type) { d.token = ""; d.secret = "" }
                d.type = t
                d.name = name.text.toString().trim().ifBlank { t.label.substringBefore(" (") }
                d.host = host.text.toString().trim()
                d.port = port.text.toString().toIntOrNull() ?: t.defaultPort
                d.mac = mac.text.toString().trim()
                d.irBrand = brands[brandSpinner.selectedItemPosition].first
                if (t == DeviceType.KODI) { d.user = user.text.toString().trim(); d.secret = pass.text.toString() }
                if (t == DeviceType.IR && !hasIr(this)) {
                    Toast.makeText(this, "Attention : aucun émetteur infrarouge détecté sur ce téléphone.", Toast.LENGTH_LONG).show()
                }
                val retest = t == DeviceType.VESTEL && (existing == null || d.secret.isBlank())
                store.save(d)
                refresh()
                if (retest) VestelSetup.start(this, d) { refresh(); if (existing == null) openRemote(d) }
                else if (existing == null) openRemote(d)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun helpFor(t: DeviceType) = when (t) {
        DeviceType.IR -> "Fonctionne sans réseau si le téléphone possède un émetteur IR (Xiaomi, Redmi, POCO, Huawei, Honor…). « Toutes marques » envoie le code Samsung, LG, Sony, Philips et Toshiba."
        DeviceType.SAMSUNG -> "Au premier envoi, la TV affiche une demande d'autorisation : choisissez « Autoriser »."
        DeviceType.LG -> "Activez « LG Connect Apps » / « Allumer via Wi-Fi » dans Réglages > Réseau. Acceptez la demande affichée sur la TV."
        DeviceType.ANDROID_TV -> "La TV affiche un code à 6 caractères lors du premier appairage. Fonctionne avec Sony, Philips, TCL, Hisense, Xiaomi, Shield, Chromecast Google TV et box Android TV."
        DeviceType.ROKU -> "Aucun réglage nécessaire. Le contrôle réseau doit être autorisé (Réglages > Système > Contrôle par applis mobiles)."
        DeviceType.SONY -> "Bravia non Android : activez Contrôle IP > Clé pré-partagée. Les Bravia Android/Google TV : préférez le type « Android TV »."
        DeviceType.PHILIPS -> "TV Philips non Android (JointSpace). Les Philips Android TV : choisissez « Android TV »."
        DeviceType.PANASONIC -> "Activez « Contrôle TV par appareil réseau » dans Réglages réseau > Paramètres TV Remote App."
        DeviceType.VESTEL -> "Smart TV non Android fabriquées par Vestel (Edenwood d'Électro Dépôt, Toshiba, Telefunken, Hitachi, JVC…). Pas d'appairage : un test « Volume + » trouve la bonne façon de lui parler. La TV doit être connectée au même Wi-Fi."
        DeviceType.FREEBOX -> "Le code à 8 chiffres sera demandé (Réglages > Système > Informations Freebox Player et Server). Laisser l'IP vide pour hd1.freebox.fr."
        DeviceType.KODI -> "Kodi : Paramètres > Services > Contrôle > « Autoriser le contrôle à distance via HTTP »."
    }

    private fun hasIr(ctx: Context): Boolean =
        (ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true
}

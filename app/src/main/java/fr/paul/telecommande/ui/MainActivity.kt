package fr.paul.telecommande.ui

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var store: DeviceStore
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Ui.systemBars(this)
        store = DeviceStore(this)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        findViewById<View>(R.id.fab).setOnClickListener { showAddMenu() }
        // Relance le suivi / l'assistant s'ils étaient actifs (après une mise à jour par exemple)
        val parental = fr.paul.telecommande.core.ParentalStore(this)
        if (store.all().any { it.isSmartBox && (it.hasBoxApp || it.monitorEnabled || parental.rules(it.id).enabled ||
                (it.ytScreenId.isNotBlank() && (it.ytAutoSkipAds || it.ytSkipSponsors))) }) {
            fr.paul.telecommande.service.AssistantService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        PhoneUpdate.checkAndOffer(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        while (list.childCount > 1) list.removeViewAt(1)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        findViewById<TextView>(R.id.greeting).text = if (hour >= 18 || hour < 5) "Bonsoir" else "Bonjour"
        val devices = store.all()
        val c = this
        if (devices.count { it.hasBoxApp } >= 1) list.addView(homeCard(devices),
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(2), dp(16), dp(18)) })
        empty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        if (devices.isNotEmpty()) list.addView(Ui.label(this, "Mes appareils"),
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(24), dp(2), dp(24), dp(6)) })
        devices.forEach { d ->
            // Carte d'appareil : pastille en dégradé Aurora + nom + détail + flèche ronde
            val card = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                elevation = dp(2).toFloat()
                setPadding(dp(16), dp(16), dp(14), dp(16))
                isClickable = true; isFocusable = true
                setOnClickListener { openRemote(d) }
                setOnLongClickListener { showDeviceMenu(d); true }
            }
            card.addView(TextView(this).apply {
                text = when (d.type) { DeviceType.IR -> "IR"; DeviceType.ANDROID_TV -> "TV"; else -> d.type.label.take(2).uppercase() }
                gravity = android.view.Gravity.CENTER; textSize = 15f; typeface = Ui.bold; setTextColor(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Ui.color(c, R.color.brand_a), Ui.color(c, R.color.brand_b))).apply { cornerRadius = dp(18).toFloat() }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(54), dp(54)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = d.name; textSize = 17f; typeface = Ui.bold; setTextColor(Ui.color(c, R.color.ink)) })
            val detail = when (d.type) {
                DeviceType.IR -> "Infrarouge · " + (IrBrands.byId(d.irBrand)?.label ?: "Toutes marques")
                DeviceType.ANDROID_TV -> "Box Android TV · assistant intelligent" + if (d.ytScreenId.isNotBlank()) " · YouTube associé" else ""
                else -> d.type.label.substringBefore(" (") + " · " + d.host.ifBlank { "hôte par défaut" }
            }
            texts.addView(TextView(this).apply { text = detail; textSize = 13f; setTextColor(Ui.color(c, R.color.muted)); maxLines = 2 })
            card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14); marginEnd = dp(8) })
            card.addView(arrowBubble(Ui.color(c, R.color.surface2), Ui.color(c, R.color.ink)))
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(6), dp(16), dp(6))
            })
        }
    }

    /** Petite bulle ronde avec une flèche « ouvrir ». */
    private fun arrowBubble(bg: Int, fg: Int) = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_down); rotation = -90f
        imageTintList = android.content.res.ColorStateList.valueOf(fg)
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(bg) }
        val p = dp(10); setPadding(p, p, p, p)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    /** Grande carte foncée « Ma maison » : toutes les TV, et le temps de TV du jour si le suivi est actif. */
    private fun homeCard(devices: List<Device>): View {
        val c = this
        val parental = fr.paul.telecommande.core.ParentalStore(this)
        val tracked = devices.filter { it.isSmartBox && (it.monitorEnabled || parental.rules(it.id).enabled) }
        val today = tracked.sumOf { fr.paul.telecommande.core.TvMonitor.todayUsage(this, it.id).values.sum() }
        val protectedCount = devices.count { it.isSmartBox && (it.hasBoxApp || parental.rules(it.id).enabled) }
        val onHero = android.graphics.Color.WHITE
        val onHeroMuted = 0xFFDCDDF5.toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_hero)
            elevation = dp(8).toFloat()
            setPadding(dp(20), dp(18), dp(18), dp(20))
            isClickable = true; isFocusable = true
            contentDescription = "Ma maison : toutes les TV, messages et récompenses"
            setOnClickListener { startActivity(Intent(c, HomeOverviewActivity::class.java)) }
        }
        val top = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        top.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.color(c, R.color.ok)) }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        top.addView(TextView(this).apply {
            text = if (protectedCount > 0) "Contrôle parental actif" + (if (protectedCount > 1) " · $protectedCount TV" else "") else "Toutes les TV de la maison"
            textSize = 13f; setTextColor(onHeroMuted)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(arrowBubble(0x26FFFFFF, onHero))
        card.addView(top)
        card.addView(TextView(this).apply {
            text = "Ma maison"; textSize = 24f; typeface = Ui.bold; setTextColor(onHero); letterSpacing = -0.01f
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        card.addView(TextView(this).apply {
            text = "Toutes les TV, messages et récompenses"; textSize = 14f; setTextColor(onHeroMuted)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        if (tracked.isNotEmpty()) {
            val stat = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x1FFFFFFF) }
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            stat.addView(TextView(this).apply { text = "Temps de TV aujourd'hui"; textSize = 13f; setTextColor(onHeroMuted) }, LinearLayout.LayoutParams(0, -2, 1f))
            stat.addView(TextView(this).apply { text = fr.paul.telecommande.core.Usage.format(today); textSize = 18f; typeface = Ui.bold; setTextColor(onHero) })
            card.addView(stat, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        return card
    }

    private fun openRemote(d: Device) {
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

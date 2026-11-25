package fr.paul.telecommande.box

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.NetworkInterface

/**
 * Écran de l'appli sur la TV : étapes d'activation (une seule obligatoire), état,
 * téléphones appairés. Entièrement pilotable à la télécommande.
 */
class SetupActivity : Activity() {
    private lateinit var root: LinearLayout
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(72), dp(48), dp(72), dp(48)) }
        setContentView(ScrollView(this).apply { background = BoxUi.screen(this@SetupActivity); addView(root) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    override fun onResume() {
        super.onResume(); render()
        if (triedAccessibility && !GuardService.enabled(this)) { triedAccessibility = false; showAccessibilityPath() }
    }

    private fun text(t: String, size: Float, color: Int = BoxUi.INK, bold: Boolean = false) = BoxUi.text(this, t, size, color, bold, center = false)

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = BoxUi.button(this, label, primary, 16f, onClick)

    private fun step(n: String, title: String, desc: String, done: Boolean?, action: String?, onClick: (() -> Unit)?) {
        val active = done == false && n == "1"
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = BoxUi.cardBg(this@SetupActivity, 24f, highlight = active)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        row.addView(BoxUi.badge(this, if (done == true) "✓" else n, when (done) { true -> true; false -> false; null -> null }, 48f))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 20f, bold = true))
        texts.addView(text(desc, 14.5f, BoxUi.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(20); marginEnd = dp(16) })
        if (action != null && onClick != null) row.addView(button(action, active, onClick))
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun render() {
        root.removeAllViews()
        val store = BoxStore.get(this)
        val guard = GuardService.enabled(this)
        root.addView(BoxUi.label(this, "Télécommande Box"))
        root.addView(text(if (guard) "La TV est protégée" else "Activez la protection", 40f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(android.view.View(this@SetupActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (guard) BoxUi.OK else BoxUi.WARN) }
            }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
            addView(text(if (guard) "Protection active sur cette TV." else "Une seule étape obligatoire, en dessous.", 17f, if (guard) BoxUi.OK else BoxUi.WARN))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(12) })

        step("1", "Activer la protection",
            "Paramètres > Accessibilité > Télécommande Box > Activer. Sert uniquement à savoir quelle appli est ouverte et à afficher l'écran « Temps écoulé ».",
            guard, if (guard) "Ouvrir" else "Activer") { openAccessibility() }

        step("↻", "Mises à jour",
            "Version ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?")}. L'appli vérifie toute seule chaque jour et vous propose les nouvelles versions sur l'écran d'accueil.",
            null, "Rechercher") { startActivity(Intent(this, UpdateActivity::class.java).putExtra(UpdateActivity.EXTRA_FORCE, true)) }

        val phones = store.phones()
        step("2", "Appairer le téléphone du parent",
            "Sur le téléphone : appli Télécommande > cette box > Contrôle parental > « Appairer l'appli Box ». Un code s'affichera ici. Adresse de la box : ${ip() ?: "inconnue"}",
            phones.isNotEmpty(), null, null)

        val usageOk = usageAccess()
        val usageAvailable = resolvable(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        step("+", "Historique des jours précédents (facultatif)",
            if (usageAvailable) "Importe le temps d'écran des derniers jours. Paramètres > Accès aux données d'utilisation." else "Non proposé par cette box : le suivi démarre aujourd'hui.",
            if (usageAvailable) usageOk else null, if (usageAvailable && !usageOk) "Autoriser" else null) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }

        val mediaAvailable = resolvable("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        step("+", "Titre en cours sur le téléphone (facultatif)",
            if (mediaAvailable) "Affiche le titre et l'image de ce qui est regardé dans l'appli du téléphone." else "Non proposé par cette box.",
            if (mediaAvailable) Media.allowed(this) else null, if (mediaAvailable && !Media.allowed(this)) "Autoriser" else null) {
            open("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        }

        step("+", "Guide des programmes (facultatif)",
            if (TvGuide.available()) "Lit la classification officielle (-10, -12, -16…) des chaînes qui publient leur grille dans Android TV." else "Non proposé par cette box.",
            if (TvGuide.available()) TvGuide.allowed(this) else null, if (TvGuide.available() && !TvGuide.allowed(this)) "Autoriser" else null) {
            if (android.os.Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(TvGuide.PERMISSION), 7)
        }

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        val isAdmin = dpm.isAdminActive(admin)
        step("+", "Empêcher la désinstallation (facultatif)",
            "Un enfant ne pourra pas supprimer l'appli sans désactiver d'abord cette option.",
            isAdmin, if (isAdmin) null else "Activer") {
            runCatching {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_desc)))
            }
        }

        if (phones.isNotEmpty()) {
            root.addView(BoxUi.label(this, "Téléphones appairés", BoxUi.MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(32) })
            phones.forEach { (fp, name) ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
                row.addView(text("$name  ·  ${fp.take(8)}…", 16f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button("Retirer") { withPin { store.removePhone(fp); render() } })
                root.addView(row)
            }
        }
        root.addView(text(
            "Confidentialité : les données restent dans votre maison (sur cette box et le téléphone appairé). Aucun compte, aucun serveur. " +
                if (store.encrypted) "Réglages chiffrés." else "Attention : le chiffrement matériel n'est pas disponible sur cette box.",
            13f, BoxUi.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        root.getChildAt(3)?.let { (it as? LinearLayout)?.getChildAt(2)?.requestFocus() }
    }

    /** Le code parent (défini sur le téléphone) protège les actions sensibles. */
    private fun withPin(action: () -> Unit) {
        val store = BoxStore.get(this)
        if (store.rules().pinHash.isBlank()) { action(); return }
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Code parent" }
        AlertDialog.Builder(this).setTitle("Code parent").setView(input)
            .setPositiveButton("Valider") { _, _ ->
                val r = store.tryPin(input.text.toString())
                if (r == fr.paul.telecommande.shared.PinThrottle.Result.Ok) action()
                else android.widget.Toast.makeText(this, fr.paul.telecommande.shared.PinThrottle.message(r), android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Annuler", null).show()
    }

    private var triedAccessibility = false

    /**
     * Ouvre l'écran Accessibilité de la TV. L'accès standard (ACTION_ACCESSIBILITY_SETTINGS) est redirigé par certaines
     * box (Freebox…) vers les Paramètres généraux : on cherche donc d'abord, dans l'appli Paramètres de la TV, l'écran
     * dont le nom parle d'accessibilité, et on ne retombe sur les Paramètres généraux qu'en dernier recours.
     */
    private fun openAccessibility() {
        triedAccessibility = true
        val me = android.content.ComponentName(this, GuardService::class.java).flattenToString()
        val candidates = mutableListOf<Intent>()
        // Android 11+ : directement la page de NOTRE service
        if (android.os.Build.VERSION.SDK_INT >= 30)
            candidates += Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, me)
        candidates += accessibilityScreens()
        candidates += Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val opened = candidates.firstOrNull { i ->
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val target = i.resolveActivity(packageManager) ?: return@firstOrNull false
            // Un accès standard redirigé vers un autre écran (Paramètres généraux) ne compte pas
            if (i.component == null && i.action == Settings.ACTION_ACCESSIBILITY_SETTINGS && !target.className.contains("ccessibility")) return@firstOrNull false
            runCatching { startActivity(i); true }.getOrDefault(false)
        }
        getSharedPreferences("box_meta", MODE_PRIVATE).edit()
            .putString("accessibilityOpen", opened?.let { it.component?.flattenToShortString() ?: it.action } ?: "aucun").apply()
        if (opened == null) showAccessibilityPath()
    }

    /** Écrans « Accessibilité » déclarés par l'appli Paramètres de la TV (noms différents selon les fabricants). */
    private fun accessibilityScreens(): List<Intent> {
        val pkgs = listOf("com.android.tv.settings", "com.android.settings") +
            runCatching { packageManager.getInstalledPackages(0).map { it.packageName }.filter { fr.paul.telecommande.shared.ProtectedApps.isSettings(it) } }.getOrDefault(emptyList())
        return pkgs.distinct().flatMap { pkg ->
            val acts = runCatching { packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities?.toList() }.getOrNull() ?: emptyList()
            acts.filter { it.exported && it.name.contains("ccessibility") && !it.name.contains("Shortcut") && !it.name.contains("Service") }
                // l'écran principal d'abord : « AccessibilityActivity », « AccessibilitySettings… »
                .sortedBy { n -> when { n.name.endsWith("AccessibilityActivity") -> 0; n.name.contains("AccessibilitySettings") -> 1; else -> 2 } }
                .map { Intent().setClassName(pkg, it.name) }
        }
    }

    private fun showAccessibilityPath() {
        AlertDialog.Builder(this).setTitle("Activer la protection")
            .setMessage("Cette TV n'ouvre pas directement l'écran Accessibilité. Dans les Paramètres :\n\n" +
                "1. Préférences de l'appareil › Accessibilité.\n2. Choisissez « Télécommande Box », puis Activer › OK.\n\n" +
                "Revenez ensuite dans cette appli : l'étape se cochera toute seule.")
            .setPositiveButton("Ouvrir les Paramètres") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .setNegativeButton("Plus tard", null).show()
    }

    private fun open(action: String) {
        runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun resolvable(action: String) = Intent(action).resolveActivity(packageManager) != null

    private fun usageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun ip(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    }.getOrNull()
}

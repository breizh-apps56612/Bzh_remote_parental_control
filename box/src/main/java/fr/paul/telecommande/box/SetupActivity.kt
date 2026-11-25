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

    /** Ouvre l'accessibilité ; certaines box (Freebox…) refusent l'accès direct : on essaie plusieurs chemins. */
    private fun openAccessibility() {
        triedAccessibility = true
        val candidates = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity"),
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings"),
            Intent(Settings.ACTION_SETTINGS)
        )
        val ok = candidates.any { i ->
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.resolveActivity(packageManager) != null && runCatching { startActivity(i); true }.getOrDefault(false)
        }
        if (!ok) showAccessibilityPath()
    }

    private fun showAccessibilityPath() {
        AlertDialog.Builder(this).setTitle("Activer la protection à la main")
            .setMessage("Avec la télécommande de la box :\n\n1. Ouvrez les Paramètres (roue dentée en haut à droite de l'accueil).\n" +
                "2. Préférences de l'appareil › Accessibilité.\n3. Choisissez « Télécommande Box », puis Activer › OK.\n\n" +
                "Revenez ensuite dans cette appli : l'étape se cochera toute seule.")
            .setPositiveButton("Compris", null).show()
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

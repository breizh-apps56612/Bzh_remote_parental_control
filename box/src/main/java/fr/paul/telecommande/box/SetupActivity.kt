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
        // Le mode d'emploi ne s'affiche que si la protection n'est vraiment pas active au retour des Paramètres
        // (avant : il pouvait sortir alors que la protection était déjà validée).
        if (triedAccessibility && GuardService.instance == null && !GuardService.enabled(this)) showAccessibilityPath()
        triedAccessibility = false
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
        changeProfileButton = null
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
            "Sur le téléphone : appli Télécommande > onglet Enfants > « Déjà installée : appairer ». Un code s'affichera ici. Adresse de la box : ${ip() ?: "inconnue"}",
            phones.isNotEmpty(), null, null)

        whoWatchesCard(store)

        val usageOk = usageAccess()
        val usageAvailable = specialScreen(Settings.ACTION_USAGE_ACCESS_SETTINGS, USAGE_WORDS) != null
        step("+", "Historique des jours précédents (facultatif)",
            if (usageAvailable) "Importe le temps d'écran des derniers jours. Paramètres > Accès aux données d'utilisation." else "Non proposé par cette box : le suivi démarre aujourd'hui.",
            if (usageAvailable) usageOk else null, if (usageAvailable && !usageOk) "Autoriser" else null) { openSpecial(Settings.ACTION_USAGE_ACCESS_SETTINGS, USAGE_WORDS, "Accès aux données d'utilisation") }

        val mediaAvailable = specialScreen("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", NOTIF_WORDS) != null
        step("+", "Titre en cours sur le téléphone (facultatif)",
            if (mediaAvailable) "Affiche le titre et l'image de ce qui est regardé dans l'appli du téléphone." else "Non proposé par cette box.",
            if (mediaAvailable) Media.allowed(this) else null, if (mediaAvailable && !Media.allowed(this)) "Autoriser" else null) {
            openSpecial("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", NOTIF_WORDS, "Accès aux notifications")
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
        // Focus de départ : « Changer de profil » quand la protection est active (le geste le plus courant),
        // sinon le bouton de l'étape 1. Avant : toujours « Ouvrir » (Accessibilité), même protection validée.
        val protectedNow = guard || GuardService.instance != null
        (changeProfileButton.takeIf { protectedNow } ?: (root.getChildAt(3) as? LinearLayout)?.getChildAt(2))?.requestFocus()
    }

    private var changeProfileButton: android.view.View? = null

    /**
     * « Qui regarde ? » : changer de profil tout de suite, et choisir ce qui se passe à l'allumage
     * (demander à chaque fois, ou appliquer un profil par défaut). Le choix de démarrage demande le code parent.
     */
    private fun whoWatchesCard(store: BoxStore) {
        val rules = store.rules()
        if (rules.profiles.isEmpty()) return
        val active = rules.profiles.firstOrNull { it.id == store.activeProfile() }
        val def = rules.startProfile()
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = BoxUi.cardBg(this@SetupActivity, 24f)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        row.addView(BoxUi.badge(this, active?.emoji ?: "?", null, 48f))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text("Qui regarde : ${active?.name ?: "personne pour l'instant"}", 20f, bold = true))
        texts.addView(text("À l'allumage : " + (def?.let { "profil ${it.emoji} ${it.name} appliqué d'office" } ?: "la TV demande « Qui regarde ? »") +
            if (def?.unlimited == true) ". Attention : sans aucune limite tant que personne ne change." else ".", 14.5f, BoxUi.MUTED),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(20); marginEnd = dp(16) })
        row.addView(button("Changer de profil", true) {
            val g = GuardService.instance
            if (g == null) android.widget.Toast.makeText(this, "Activez d'abord la protection (étape 1).", android.widget.Toast.LENGTH_LONG).show()
            else { g.askWhoWatches(); moveTaskToBack(true) }
        }.also { changeProfileButton = it })
        row.addView(button("Au démarrage") { withPin { chooseStartProfile() } }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun chooseStartProfile() {
        val store = BoxStore.get(this)
        val rules = store.rules()
        val choices = listOf("" to "Demander « Qui regarde ? » à chaque allumage") +
            rules.profiles.map { it.id to "Toujours ${it.emoji} ${it.name}" + if (it.unlimited) " (sans limite)" else "" }
        AlertDialog.Builder(this).setTitle("À l'allumage de la TV")
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(),
                choices.indexOfFirst { it.first == (rules.startProfile()?.id ?: "") }.coerceAtLeast(0)) { d, i ->
                d.dismiss()
                // version plus récente : le téléphone reprendra ce choix à sa prochaine lecture des règles
                store.saveRules(rules.copy(defaultProfile = choices[i].first, version = System.currentTimeMillis()))
                render()
            }
            .setNegativeButton("Annuler", null).show()
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

    private val NOTIF_WORDS = listOf("NotificationAccess", "NotificationListener", "NotificationAccessSettings")
    private val USAGE_WORDS = listOf("UsageAccess", "UsageStats", "AppUsageAccess")

    /**
     * Écran « Accès spéciaux » (notifications, données d'utilisation) : l'accès standard, sinon l'écran trouvé dans l'appli
     * Paramètres de la TV par son nom (certaines box, dont la Freebox, ne déclarent pas l'accès standard). Null = vraiment absent.
     */
    private fun specialScreen(action: String, words: List<String>): Intent? {
        Intent(action).takeIf { it.resolveActivity(packageManager) != null }?.let { return it }
        val pkgs = listOf("com.android.tv.settings", "com.android.settings") +
            runCatching { packageManager.getInstalledPackages(0).map { it.packageName }.filter { fr.paul.telecommande.shared.ProtectedApps.isSettings(it) } }.getOrDefault(emptyList())
        for (pkg in pkgs.distinct()) {
            val acts = runCatching { packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities?.toList() }.getOrNull() ?: continue
            acts.firstOrNull { a -> a.exported && words.any { w -> a.name.contains(w, ignoreCase = true) } }?.let { return Intent().setClassName(pkg, it.name) }
        }
        // Dernier recours : l'écran « Accès spéciaux des applications », d'où l'on choisit la ligne à la main
        for (pkg in pkgs.distinct()) {
            val acts = runCatching { packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities?.toList() }.getOrNull() ?: continue
            acts.firstOrNull { a -> a.exported && a.name.contains("SpecialAppAccess", ignoreCase = true) }?.let { return Intent().setClassName(pkg, it.name) }
        }
        return null
    }

    private fun openSpecial(action: String, words: List<String>, line: String) {
        val i = specialScreen(action, words)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = i != null && runCatching { startActivity(i) }.isSuccess
        getSharedPreferences("box_meta", MODE_PRIVATE).edit().putString("special_$line", i?.component?.flattenToShortString() ?: i?.action ?: "aucun").apply()
        BoxLog.init(this); BoxLog.log("CONFIG", "$line : " + (i?.component?.flattenToShortString() ?: i?.action ?: "aucun écran trouvé") + if (ok) " (ouvert)" else " (échec)")
        if (!ok || i?.component?.className?.contains("SpecialAppAccess", true) == true)
            AlertDialog.Builder(this).setTitle(line)
                .setMessage("Dans les Paramètres de la TV : Applications › Accès spéciaux des applications › $line › « Télécommande Box » › Autoriser.\n\n" +
                    "Si cette ligne n'existe pas, la box ne la propose pas : la TV se fiera alors à la classification affichée à l'écran.")
                .setPositiveButton("Compris", null).show()
    }

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

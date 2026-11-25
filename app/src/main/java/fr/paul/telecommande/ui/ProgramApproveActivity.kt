package fr.paul.telecommande.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.DeviceStore
import fr.paul.telecommande.core.DiagLog
import fr.paul.telecommande.core.ParentalStore
import fr.paul.telecommande.shared.AllowedProgram
import fr.paul.telecommande.shared.ProgramFilter
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * La TV a bloqué un programme (trop âgé, contenu interdit, classification inconnue) et l'enfant demande.
 * Le parent valide avec son empreinte, puis choisit : cette fois, aujourd'hui, toujours pour cet enfant ou pour toute la famille.
 */
class ProgramApproveActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent.getStringExtra("device")?.let { DeviceStore(this).get(it) } ?: run { finish(); return }
        val id = intent.getStringExtra("id") ?: run { finish(); return }
        val title = intent.getStringExtra("title").orEmpty()
        val reason = intent.getStringExtra("reason").orEmpty()
        val label = intent.getStringExtra("label").orEmpty()
        val profileId = intent.getStringExtra("profile").orEmpty()
        val profileName = intent.getStringExtra("profileName").orEmpty()
        val who = profileName.ifBlank { "Un enfant" }
        val what = title.ifBlank { "un programme sur $label" }

        fun respond(scope: String, always: String? = null) = lifecycleScope.launch {
            runCatching {
                val c = BoxClient.of(this@ProgramApproveActivity, device)
                if (always != null) {
                    // « Toujours » : enregistré dans les règles (téléphone + TV), pour l'enfant ou toute la famille
                    val ps = ParentalStore(this@ProgramApproveActivity)
                    val r = ps.boxRules(device.id)
                    val k = ProgramFilter.key(title)
                    r.allowedPrograms.removeAll { it.key == k && it.profile == always }
                    r.allowedPrograms += AllowedProgram(k, title, always)
                    r.pinHash = ps.boxPinHash; r.version = System.currentTimeMillis()
                    ps.saveBoxRules(device.id, r)
                    c.call("set_rules", JSONObject().put("rules", r.toJson()))
                }
                c.call("program_respond", JSONObject().put("id", id).put("scope", scope))
            }.onSuccess {
                DiagLog.log("PARENTAL", "Programme « $what » : ${scopeLabel(scope, always)}")
                Ui.toast(this@ProgramApproveActivity, if (scope == "refuse") "Refusé" else "Autorisé sur ${device.name}")
            }.onFailure { Ui.toast(this@ProgramApproveActivity, it.message ?: "TV injoignable") }
            finish()
        }

        fun choices() {
            val opts = mutableListOf("Cette fois" to { respond("once") }, "Aujourd'hui" to { respond("today") })
            if (title.isNotBlank()) {
                if (profileId.isNotBlank()) opts += "Toujours pour ${profileName.ifBlank { "cet enfant" }}" to { respond("always", profileId) }
                opts += "Toujours pour toute la famille" to { respond("always", "") }
            }
            AlertDialog.Builder(this).setTitle("Autoriser « ${what.take(50)} »")
                .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
                .setNegativeButton("Annuler") { _, _ -> finish() }
                .setOnCancelListener { finish() }.show()
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val p = Ui.dp(this@ProgramApproveActivity, 24); setPadding(p, Ui.dp(this@ProgramApproveActivity, 26), p, Ui.dp(this@ProgramApproveActivity, 14))
        }
        val c = this
        var dialog: AlertDialog? = null
        box.addView(Ui.badge(c, if (intent.getBooleanExtra("unknown", false)) "❔" else "🚫").apply { textSize = 26f }, LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 64)))
        box.addView(Ui.text(c, "$who veut regarder", 15f, muted = true).apply { gravity = Gravity.CENTER }, Ui.lp(c, 14))
        box.addView(Ui.text(c, "« ${what.take(70)} »", 19f).apply { typeface = Ui.bold; gravity = Gravity.CENTER }, Ui.lp(c, 4))
        box.addView(Ui.text(c, reason, 13.5f, muted = true).apply { gravity = Gravity.CENTER }, Ui.lp(c, 8))
        box.addView(Ui.button(c, "Autoriser…", primary = true) {
            dialog?.setOnCancelListener(null); dialog?.dismiss()
            Ui.requirePin(this, "Autoriser « ${what.take(40)} » sur ${device.name}", cancel = { finish() }) { choices() }
        }, LinearLayout.LayoutParams(-1, Ui.dp(c, 54)).apply { topMargin = Ui.dp(c, 20) })
        box.addView(Ui.button(c, "Refuser") { dialog?.setOnCancelListener(null); dialog?.dismiss(); respond("refuse") },
            LinearLayout.LayoutParams(-1, Ui.dp(c, 50)).apply { topMargin = Ui.dp(c, 10) })
        dialog = AlertDialog.Builder(this).setView(box).setOnCancelListener { finish() }.show()
    }

    private fun scopeLabel(scope: String, always: String?) = when (scope) {
        "refuse" -> "refusé"; "today" -> "autorisé aujourd'hui"; "once" -> "autorisé cette fois"
        else -> if (always.isNullOrBlank()) "toujours autorisé (famille)" else "toujours autorisé (enfant)"
    }
}

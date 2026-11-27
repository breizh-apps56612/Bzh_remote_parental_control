package fr.paul.telecommande.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.R
import fr.paul.telecommande.core.BoxClient
import fr.paul.telecommande.core.Device
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Actions vers l'appli Box partagées par la télécommande, le tableau de bord et « Ma maison ». */
object BoxActions {

    private fun call(a: AppCompatActivity, d: Device, cmd: String, params: JSONObject, ok: String? = null) {
        a.lifecycleScope.launch {
            runCatching { BoxClient.of(a, d).call(cmd, params) }
                .onSuccess { ok?.let { Ui.toast(a, it) } }
                .onFailure { Ui.toast(a, it.message ?: "La TV ne répond pas") }
        }
    }

    // ---------- Message en plein écran ----------

    private val PRESETS = listOf(
        Triple("🍽️", "À table !", "Le repas est prêt."),
        Triple("🌙", "Au lit !", "C'est l'heure d'aller se coucher."),
        Triple("📚", "Les devoirs", "On éteint et on fait ses devoirs."),
        Triple("🚗", "On part !", "Chaussures et manteau, on y va.")
    )

    fun message(a: AppCompatActivity, d: Device) {
        val sheet = TileSheet(a)
        val tints = listOf(TileSheet.AMBER, TileSheet.INDIGO, TileSheet.MINT, TileSheet.SKY)
        sheet.show(TileSheet.Page("💬", "Message", "Il s'affiche en grand sur ${d.name} (la vidéo se met en pause)") { sh, col ->
            sh.sections(col, TileSheet.Section(null, PRESETS.mapIndexed { i, (emoji, title, text) ->
                TileSheet.Tile(emoji, title, text, tints[i % tints.size]) { sheet.dismiss(); send(a, d, emoji, title, text) }
            } + TileSheet.Tile("✏️", "Votre message", "Écrire ce que vous voulez", TileSheet.VIOLET) { sheet.dismiss(); custom(a, d) }))
        })
    }

    private fun custom(a: AppCompatActivity, d: Device) {
        val input = EditText(a).apply { hint = "Votre message"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        val box = LinearLayout(a).apply { setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(a).setTitle("Message sur ${d.name}").setView(box)
            .setPositiveButton("Envoyer") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { send(a, d, "📣", it, "") } }
            .setNegativeButton("Annuler", null).show()
    }

    private fun send(a: AppCompatActivity, d: Device, emoji: String, title: String, text: String) =
        call(a, d, "message", JSONObject().put("emoji", emoji).put("title", title).put("text", text).put("pause", true), "Message affiché sur la TV")

    // ---------- Récompense (tâche accomplie → minutes en plus) ----------

    private val TASKS = listOf("📚 Devoirs faits" to 15, "🧸 Chambre rangée" to 10, "🍽️ Table débarrassée" to 10, "🐾 Aide à la maison" to 15, "⭐ Bravo !" to 5)

    fun reward(a: AppCompatActivity, d: Device) {
        Ui.requirePin(a, "Accorder une récompense ?") {
            val sheet = TileSheet(a)
            sheet.show(TileSheet.Page("🎉", "Récompense", "« Bravo ! » s'affiche sur ${d.name}, avec le temps en plus") { sh, col ->
                sh.sections(col, TileSheet.Section(null, TASKS.map { (label, min) ->
                    TileSheet.Tile(label.substringBefore(" "), label.substringAfter(" "), "+$min min", TileSheet.AMBER) {
                        sheet.dismiss()
                        call(a, d, "bonus", JSONObject().put("pkg", "*").put("minutes", min).put("reason", label), "+$min min accordées")
                    }
                }))
            })
        }
    }

    // ---------- Vraies applis de la TV (avec icônes) ----------

    /** Les applis de la TV en tuiles (avec leurs vraies icônes), dans le volet premium. */
    fun appsSheet(a: AppCompatActivity, d: Device) {
        val sheet = TileSheet(a)
        var apps: List<Triple<String, String, android.graphics.Bitmap?>>? = null
        var error: String? = null
        sheet.show(TileSheet.Page("▦", "Applis", "Ouvrir une appli sur ${d.name}") { sh, col ->
            val list = apps
            when {
                error != null -> col.addView(Ui.card(a).apply { addView(Ui.text(a, error ?: "", 15f)) })
                list == null -> col.addView(LinearLayout(a).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(a, 30), 0, Ui.dp(a, 30)); addView(ProgressBar(a)) })
                list.isEmpty() -> col.addView(Ui.card(a).apply { addView(Ui.text(a, "Aucune appli trouvée sur la TV.", 15f)) })
                else -> sh.sections(col, TileSheet.Section(null, list.map { (pkg, label, icon) ->
                    TileSheet.Tile("📺", label, null, icon = icon) {
                        sheet.dismiss()
                        call(a, d, "launch", JSONObject().put("pkg", pkg), "Ouverture de $label…")
                    }
                }, 3))
            }
        })
        a.lifecycleScope.launch {
            val res = runCatching { BoxClient.of(a, d).call("apps", JSONObject().put("icons", true)) }.getOrElse {
                error = it.message ?: "La TV ne répond pas"; sheet.refresh(); return@launch
            }
            val arr = res.optJSONArray("apps")
            apps = (0 until (arr?.length() ?: 0)).map { i ->
                val o = arr!!.getJSONObject(i)
                val icon = o.optString("icon").takeIf { it.isNotEmpty() }?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                Triple(o.optString("pkg"), o.optString("label"), icon)
            }
            sheet.refresh()
        }
    }

    // ---------- Profil « Qui regarde ? » choisi depuis le téléphone ----------

    /** « Qui regarde ? » depuis le téléphone : une tuile par profil. */
    fun pickProfile(a: AppCompatActivity, d: Device, profiles: List<fr.paul.telecommande.shared.Profile>, done: () -> Unit = {}) {
        if (profiles.isEmpty()) { Ui.toast(a, "Aucun profil : ajoutez un enfant dans l'onglet Enfants"); return }
        val sheet = TileSheet(a)
        sheet.show(TileSheet.Page("👀", "Qui regarde ?", "Le profil appliqué maintenant sur ${d.name}") { sh, col ->
            sh.sections(col, TileSheet.Section(null, profiles.map { p ->
                TileSheet.Tile(p.emoji, p.name, if (p.unlimited) "Aucune limite · code parent" else p.age()?.let { "$it ans" } ?: "Enfant",
                    if (p.unlimited) TileSheet.AMBER else TileSheet.INDIGO) {
                    sheet.dismiss()
                    Ui.requirePin(a, "Changer de profil ?") {
                        a.lifecycleScope.launch {
                            runCatching { BoxClient.of(a, d).call("set_profile", JSONObject().put("id", p.id)) }
                                .onSuccess { Ui.toast(a, "Profil ${p.name} actif"); done() }
                                .onFailure { Ui.toast(a, it.message ?: "La TV ne répond pas") }
                        }
                    }
                }
            }))
        })
    }

    // ---------- Diagnostic ----------

    /** Lignes lisibles du rapport d'apprentissage de la TV (ce qu'elle sait lire, appli par appli). */
    fun reportLines(res: org.json.JSONObject): List<String> {
        val perms = res.optJSONObject("perms")
        val head = mutableListOf<String>()
        if (perms?.optBoolean("media") == false) head += "⚠️ Sur la TV, autorisez « Titre en cours » (appli de la TV) : sans elle, les titres ne sont pas lus."
        if (perms?.optBoolean("guideAvailable") == true && !perms.optBoolean("guide")) head += "ℹ️ Facultatif : autorisez « Guide des programmes » sur la TV pour la classification officielle des chaînes."
        val arr = res.optJSONArray("apps") ?: org.json.JSONArray()
        val apps = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optInt("samples") > 0 }.map { o ->
            val n = o.optInt("samples").coerceAtLeast(1)
            fun pct(k: String) = if (o.optInt(k) > 0) "✓" else "—"
            val sources = listOf("annonce du lecteur" to pct("playerCue"), "écran" to pct("ratingScreen"), "infos média" to pct("ratingMeta"), "guide" to pct("ratingGuide"))
                .joinToString(" · ") { "${it.first} ${it.second}" }
            "${o.optString("label")} — titre ${pct("title")} · classification : $sources" +
                (o.optString("lastRating").takeIf { it.isNotBlank() }?.let { "\nDernière lue : $it" + (o.optString("lastRatingTitle").takeIf { t -> t.isNotBlank() }?.let { t -> " (« $t »)" } ?: "") } ?: "") +
                (o.optString("lastCue").takeIf { it.isNotBlank() }?.let { "\nAnnonce lue : « $it »" } ?: "") +
                "\n($n relevés)"
        }
        return head + apps.ifEmpty { listOf("Rien d'appris pour l'instant : la TV apprend toute seule pendant que vous regardez des programmes.") }
    }

    /** Ce que la TV a appris toute seule (en arrière-plan) : d'où viennent titres et classifications, appli par appli. */
    fun programReport(a: AppCompatActivity, d: Device) {
        a.lifecycleScope.launch {
            val res = runCatching { BoxClient.of(a, d).call("prog_report") }.getOrElse {
                Ui.toast(a, if (it.message?.contains("inconnue") == true) "Mettez d'abord à jour l'appli de la TV" else it.message ?: "La TV ne répond pas"); return@launch }
            AlertDialog.Builder(a).setTitle("Lecture des programmes").setItems(reportLines(res).toTypedArray(), null)
                .setPositiveButton("Fermer", null).show()
        }
    }

    fun diagnostics(a: AppCompatActivity, d: Device) {
        a.lifecycleScope.launch {
            val res = runCatching { BoxClient.of(a, d).call("diag") }.getOrElse { Ui.toast(a, it.message ?: "La TV ne répond pas"); return@launch }
            val arr = res.optJSONArray("events")
            val fmt = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.FRANCE)
            val lines = if (arr == null || arr.length() == 0) listOf("Aucun incident enregistré. Tout va bien 👍")
                else (arr.length() - 1 downTo 0).map { i ->
                    val e = arr.getJSONObject(i)
                    val what = when (e.optString("kind")) { "crash" -> "💥 Appli plantée"; "reboot" -> "🔄 Redémarrage"; "sleep" -> "🌙 Veille auto"; else -> "ℹ️" }
                    "$what · ${fmt.format(java.util.Date(e.optLong("at")))}\n${e.optString("text")}"
                }
            AlertDialog.Builder(a).setTitle("Santé de ${d.name}").setItems(lines.toTypedArray(), null).setPositiveButton("Fermer", null)
                .setNeutralButton("Lecture des programmes") { _, _ -> programReport(a, d) }.show()
        }
    }
}

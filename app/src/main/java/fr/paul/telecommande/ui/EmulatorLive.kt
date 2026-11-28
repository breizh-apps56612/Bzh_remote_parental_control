package fr.paul.telecommande.ui

import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fr.paul.telecommande.core.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Easter egg du menu Administrateur : « Émulateur en direct ».
 *
 * Lance le workflow GitHub « Émulateur en direct » (une touche « Run workflow » sur la page GitHub, où le
 * propriétaire est déjà connecté), puis suit son démarrage étape par étape et ouvre le lien des émulateurs dès qu'il est prêt.
 *
 * Sécurité : aucun jeton dans l'appli. Le suivi n'utilise que l'API publique de GitHub (dépôt public, lecture seule) ;
 * le lancement et l'arrêt se font sur GitHub avec le compte du propriétaire ; la page des émulateurs reste protégée
 * par le mot de passe du secret EMULATEUR_MOT_DE_PASSE.
 */
object EmulatorLive {
    private const val REPO = "breizh-apps56612/Bzh_remote_parental_control"
    private const val WORKFLOW = "emulateur-direct.yml"
    private const val API = "https://api.github.com/repos/$REPO"
    private val LINK = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")

    fun open(a: AppCompatActivity) {
        val c = a
        val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), Ui.dp(c, 8)) }
        val intro = Ui.text(c, "1. Touchez « Lancer sur GitHub », puis « Run workflow » sur la page qui s'ouvre.\n" +
            "2. Revenez ici : les étapes s'affichent, et le lien s'ouvre dès que les émulateurs sont prêts (≈ 5 min).\n" +
            "Identifiant : telecommande · mot de passe : celui du secret GitHub.", 14f)
        val status = Ui.text(c, "En attente du lancement…", 14f, muted = true)
        val steps = Ui.text(c, "", 13.5f)
        root.addView(intro); root.addView(status, Ui.lp(c, 14)); root.addView(steps, Ui.lp(c, 8))
        var link: String? = null
        var runUrl: String? = null
        val since = System.currentTimeMillis() - 2 * 60_000L   // un run lancé juste avant l'ouverture compte aussi

        val dialog = AlertDialog.Builder(a).setTitle("🧪 Émulateur en direct")
            .setView(ScrollView(c).apply { addView(root) })
            .setPositiveButton("Lancer sur GitHub", null)
            .setNeutralButton("Arrêter / voir le run", null)
            .setNegativeButton("Fermer", null).create()
        var poll: Job? = null
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val l = link
                if (l != null) view(a, l) else view(a, "https://github.com/$REPO/actions/workflows/$WORKFLOW")
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                view(a, runUrl ?: "https://github.com/$REPO/actions/workflows/$WORKFLOW")
            }
            poll = a.lifecycleScope.launch {
                var runId = 0L; var jobId = 0L; var opened = false
                while (isActive) {
                    val r = runCatching {
                        if (runId == 0L) {
                            val runs = get("$API/actions/workflows/$WORKFLOW/runs?per_page=3").optJSONArray("workflow_runs")
                            for (i in 0 until (runs?.length() ?: 0)) {
                                val run = runs!!.getJSONObject(i)
                                if (iso(run.optString("created_at")) >= since && run.optString("status") != "completed") {
                                    runId = run.optLong("id"); runUrl = run.optString("html_url"); break
                                }
                            }
                            if (runId == 0L) "Pas encore de session lancée." else "Session trouvée, démarrage…"
                        } else {
                            val jobs = get("$API/actions/runs/$runId/jobs").optJSONArray("jobs")
                            val job = jobs?.optJSONObject(0)
                            if (job == null) "Démarrage de la machine GitHub…" else {
                                jobId = job.optLong("id")
                                val arr = job.optJSONArray("steps")
                                val lines = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
                                    .filter { !it.optString("name").startsWith("Set up job") && !it.optString("name").startsWith("Post ") && !it.optString("name").startsWith("Complete job") }
                                    .joinToString("\n") { s ->
                                        val icon = when {
                                            s.optString("conclusion") == "success" -> "✅"
                                            s.optString("conclusion") == "skipped" -> "⏭️"
                                            s.optString("conclusion").isNotBlank() -> "❌"
                                            s.optString("status") == "in_progress" -> "⏳"
                                            else -> "·"
                                        }
                                        "$icon  ${s.optString("name")}"
                                    }
                                withContext(Dispatchers.Main) { steps.text = lines }
                                val ouvrir = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.firstOrNull { it.optString("name").startsWith("Ouvrir") }
                                if (link == null && ouvrir?.optString("conclusion") == "success") {
                                    val notes = getArray("$API/check-runs/$jobId/annotations")
                                    for (i in 0 until notes.length()) LINK.find(notes.getJSONObject(i).optString("message"))?.let { link = it.value }
                                }
                                when {
                                    job.optString("status") == "completed" -> "Session terminée (${job.optString("conclusion")})."
                                    link != null -> "Émulateurs prêts."
                                    ouvrir?.optString("conclusion")?.let { it.isNotBlank() && it != "success" } == true -> "Échec à l'ouverture : voir le run."
                                    else -> "Démarrage en cours…"
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        status.text = r.getOrElse { "GitHub injoignable pour l'instant (${it.message?.take(60)}), nouvel essai…" }
                        val l = link
                        if (l != null) {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "Ouvrir les émulateurs"
                            if (!opened) { opened = true; DiagLog.log("APP", "Émulateur en direct prêt"); view(a, l) }
                        }
                    }
                    if (r.getOrNull()?.startsWith("Session terminée") == true) break
                    delay(20_000)   // API publique : 60 requêtes / heure, une toutes les 20 s suffit
                }
            }
        }
        dialog.setOnDismissListener { poll?.cancel() }
        dialog.show()
    }

    private fun view(a: AppCompatActivity, url: String) =
        runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { Ui.toast(a, "Aucun navigateur pour ouvrir le lien") }

    /** Date GitHub « 2026-11-28T01:23:45Z » (sans java.time : l'appli tourne dès Android 5). */
    private fun iso(s: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    }.getOrDefault(0L)

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Telecommande-app")
        }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("réponse ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private suspend fun get(url: String) = JSONObject(fetch(url))
    private suspend fun getArray(url: String) = org.json.JSONArray(fetch(url))
}

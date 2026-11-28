package fr.paul.telecommande.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import fr.paul.telecommande.drivers.Proto
import fr.paul.telecommande.drivers.ProtoMessage
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Journal de diagnostic du téléphone : tout ce qui est envoyé à la TV et tout ce qu'elle répond,
 * horodaté à la milliseconde. Écrit ligne par ligne sur le disque (rien n'est perdu si la box plante).
 * Reste sur le téléphone : il n'est partagé que si l'utilisateur appuie sur « Exporter ».
 */
object DiagLog {
    private const val MAX_BYTES = 1_000_000L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "diaglog").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.FRANCE)
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = context.applicationContext.filesDir
        val v = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        log("APP", "Démarrage · appli $v · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // écriture synchrone : l'appli va s'arrêter
            runCatching { appendNow(line("CRASH", "Thread ${t.name} : " + android.util.Log.getStackTraceString(e))) }
            previous?.uncaughtException(t, e)
        }
    }

    private fun current() = dir?.let { File(it, "diag.log") }
    private fun old() = dir?.let { File(it, "diag.old.log") }

    private fun line(tag: String, msg: String) = "${synchronized(fmt) { fmt.format(Date()) }} [$tag] $msg\n"

    private fun appendNow(text: String) {
        val f = current() ?: return
        if (f.length() > MAX_BYTES) { old()?.delete(); f.renameTo(old()!!) }
        FileWriter(current()!!, true).use { it.write(text) }
    }

    fun log(tag: String, msg: String) {
        val text = line(tag, msg)
        io.execute { runCatching { appendNow(text) } }
    }

    fun clear() { io.execute { current()?.delete(); old()?.delete() } }

    /** Les [n] dernières lignes du journal du téléphone (pour l'afficher dans le menu Administrateur). */
    fun tail(n: Int = 300): String = runCatching {
        val lines = (old()?.takeIf { it.exists() }?.readLines() ?: emptyList()) + (current()?.takeIf { it.exists() }?.readLines() ?: emptyList())
        lines.takeLast(n).joinToString("\n")
    }.getOrDefault("")

    /** Taille totale du journal, en octets. */
    fun size(): Long = (current()?.length() ?: 0L) + (old()?.length() ?: 0L)

    // ---------- Lecture lisible des messages du protocole Android TV ----------

    private val names = mapOf(
        1 to "configure", 2 to "set_active", 3 to "error", 8 to "ping", 9 to "pong",
        10 to "touche", 20 to "clavier_état", 21 to "clavier_édition", 22 to "clavier_affiché",
        30 to "voix_début", 31 to "voix_données", 32 to "voix_fin", 40 to "allumage", 41 to "pairing_ok",
        50 to "volume", 90 to "lien_appli"
    )

    /** Messages du clavier : ce qui est tapé (code parent, mots de passe, recherches) ne va jamais dans le journal. */
    private val KEYBOARD = setOf(20, 21, 22)
    private val PACKAGE = Regex("^[a-zA-Z][\\w]*(\\.[\\w]+)+$")

    fun proto(raw: ByteArray): String = runCatching {
        val m = Proto.parse(raw)
        m.fields.entries.joinToString(" ") { (f, vs) -> (names[f] ?: "f$f") + "=" + vs.joinToString(",") { value(it, 0, f in KEYBOARD) } }
    }.getOrElse { "(${raw.size} octets illisibles)" }

    private fun value(v: Any, depth: Int, mask: Boolean = false): String = when (v) {
        is Long -> v.toString()
        is ByteArray -> {
            val text = runCatching { String(v, Charsets.UTF_8) }.getOrNull()
            val printable = text != null && v.isNotEmpty() && text.all { it >= ' ' && it != '\uFFFD' }
            val sub = if (depth < 4) runCatching { Proto.parse(v) }.getOrNull()?.takeIf { it.fields.isNotEmpty() } else null
            when {
                v.size > 2048 -> "<${v.size} octets>"
                printable && (sub == null || text!!.length > 2) ->
                    if (mask && !PACKAGE.matches(text!!)) "«texte masqué, ${text.length} car.»" else "\"" + text!!.take(200) + "\""
                sub != null -> "{" + msg(sub, depth + 1, mask) + "}"
                else -> "<${v.size} octets>"
            }
        }
        else -> v.toString()
    }

    private fun msg(m: ProtoMessage, depth: Int, mask: Boolean = false): String =
        m.fields.entries.joinToString(" ") { (f, vs) -> "$f:" + vs.joinToString(",") { value(it, depth, mask) } }

    // ---------- Export ----------

    /** Regroupe le journal dans un fichier texte et ouvre le menu de partage d'Android. */
    private const val CLAUDE_PKG = "com.anthropic.claude"

    private val CLAUDE_PROMPT = """Voici le journal de diagnostic de mon application « Entente » (télécommande Android pour box Android TV).
Explique-moi en termes simples ce qui se passe et ce que je dois faire pour régler le problème.
Repères : ATV→ = envoyé à la TV, ATV← = reçu de la TV, APK = envoi de l'appli Box par le téléphone, BT = souris Bluetooth, PARENTAL = contrôle parental, BLOQUÉ = action refusée par sécurité. À la fin : le journal de la TV (APPLI, CLIC, ÉCRAN = nombre de textes lus, contenu masqué, LECTURE, PROGRAMME, BLOCAGE).
Le problème que je rencontre : """

    fun claudeInstalled(c: Context) = runCatching { c.packageManager.getPackageInfo(CLAUDE_PKG, 0); true }.getOrDefault(false)

    /** Journal de chaque box appairée (appli Box), récupéré au moment de l'export. */
    private fun boxLogs(c: Context): String = buildString {
        val boxes = runCatching { DeviceStore(c).all().filter { it.hasBoxApp } }.getOrDefault(emptyList())
        for (d in boxes) {
            append("\n\n========== Journal de la TV « ${d.name} » ==========\n")
            val res = runCatching {
                kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(25_000) { BoxClient.of(c, d).call("box_log", org.json.JSONObject().put("max", 1_200_000)) } }
            }
            res.onFailure { append("TV injoignable (${it.message ?: it.javaClass.simpleName}) : allumez-la et réessayez.\n") }
            res.getOrNull()?.let { r ->
                if (!r.optBoolean("ok")) { append("Réponse : ${r.optString("error", "commande inconnue (mettez l'appli TV à jour)")}\n"); return@let }
                r.optString("state").takeIf { it.isNotBlank() }?.let { append("État au moment de l'export : $it\n\n") }
                append(r.optString("log"))
                r.optJSONArray("diag")?.takeIf { it.length() > 0 }?.let { arr ->
                    append("\n--- Décisions du contrôle parental (les plus récentes) ---\n")
                    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { e ->
                        append("${f.format(Date(e.optLong("at")))} [${e.optString("kind")}] ${e.optString("text")}" +
                            (e.optString("app").takeIf { it.isNotBlank() }?.let { " · appli $it" } ?: "") + "\n")
                    }
                }
                r.optJSONArray("learn")?.let { append("\n--- Ce que la TV a appris des applis ---\n${it.toString(1)}\n") }
            }
        }
    }

    /** Résumé en tête de fichier : version de l'appli, appareils enregistrés, lexique des étiquettes. */
    private fun header(c: Context): String = buildString {
        val v = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        append("Appli téléphone : ${v?.versionName ?: "?"} (${v?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: "?"})\n")
        append("Export : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date())}\n")
        runCatching { DeviceStore(c).all() }.getOrDefault(emptyList()).forEach { d ->
            append("Appareil : ${d.name} · ${d.type} · ${d.host.ifBlank { "adresse inconnue" }}${if (d.hasBoxApp) " · appli Box appairée" else ""}\n")
        }
        append("Lexique TV : SORTIE = une appli quitte l'écran et pourquoi · ACTION = ce que la Box a fait elle-même · TOUCHE = touche de télécommande · " +
            "FENÊTRE = écran affiché par la Box · TÉLÉPHONE = commande reçue du téléphone · ÉTAT = mémoire, profil, version · DÉCISION = choix du contrôle parental\n")
    }

    fun export(a: Activity, toClaude: Boolean = false) {
        if (runCatching { DeviceStore(a).all().any { it.hasBoxApp } }.getOrDefault(false))
            android.widget.Toast.makeText(a, "Récupération du journal de la TV…", android.widget.Toast.LENGTH_SHORT).show()
        io.execute {
            val out = File(a.cacheDir, "journal").apply { mkdirs() }
            val name = "journal-telecommande-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(Date()) + ".txt"
            val file = File(out, name)
            out.listFiles()?.forEach { if (it != file) it.delete() }
            file.writeText(buildString {
                append("Journal de diagnostic Entente\n")
                append("Téléphone : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
                append(header(a))
                append("\n========== Journal du téléphone ==========\n")
                old()?.takeIf { it.exists() }?.let { append(it.readText()) }
                current()?.takeIf { it.exists() }?.let { append(it.readText()) }
                append(boxLogs(a))
            })
            a.runOnUiThread {
                val uri = FileProvider.getUriForFile(a, a.packageName + ".files", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (toClaude) {
                    log("APP", "Envoi du journal à l'appli Claude")
                    send.putExtra(Intent.EXTRA_TEXT, CLAUDE_PROMPT)
                    send.clipData = android.content.ClipData.newUri(a.contentResolver, name, uri)
                    if (claudeInstalled(a)) {
                        runCatching { a.startActivity(Intent(send).setPackage(CLAUDE_PKG)) }
                            .onFailure { a.startActivity(Intent.createChooser(send, "Analyser avec…")) }
                    } else {
                        android.widget.Toast.makeText(a, "Appli Claude introuvable : choisissez où envoyer le journal.", android.widget.Toast.LENGTH_LONG).show()
                        a.startActivity(Intent.createChooser(send, "Envoyer le journal"))
                    }
                } else a.startActivity(Intent.createChooser(send, "Envoyer le journal"))
            }
        }
    }
}

package fr.paul.telecommande.shared

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Mise à jour automatique hors Play Store.
 *  1. lit https://telecommande-box.netlify.app/version.json (une fois par jour au plus) ;
 *  2. télécharge l'APK, vérifie l'empreinte SHA-256 annoncée ET que l'APK est signé avec la même
 *     clé que l'appli installée (sinon : refus, rien n'est installé) ;
 *  3. lance l'installation par PackageInstaller : Android affiche « Installer » (un clic).
 * Les réglages et l'appairage sont conservés (mise à jour, pas réinstallation).
 */
class Updater(private val ctx: Context, private val channel: String) {
    companion object {
        const val SITE = "https://telecommande-box.netlify.app"
        const val VERSION_URL = "$SITE/version.json"
        private const val DAY = 24 * 3600_000L
    }

    data class Release(val versionCode: Int, val versionName: String, val url: String, val sha256: String, val notes: String)

    private val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val app = ctx.applicationContext

    fun currentVersionCode(): Int = runCatching {
        val p = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
    }.getOrDefault(0)

    /** Version plus récente disponible (null sinon). [force] ignore la limite d'une vérification par jour. Réseau : hors fil principal. */
    fun check(force: Boolean = false): Release? {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) < DAY) return cached()
        val json = runCatching { httpText(VERSION_URL) }.getOrNull() ?: return cached()
        prefs.edit().putLong("lastCheck", now).putString("json", json).apply()
        return cached()
    }

    private fun cached(): Release? = runCatching {
        val o = JSONObject(prefs.getString("json", null) ?: return null).getJSONObject(channel)
        val r = Release(o.getInt("versionCode"), o.optString("versionName"), o.getString("url"), o.getString("sha256").lowercase(), o.optString("notes"))
        r.takeIf { it.versionCode > currentVersionCode() }
    }.getOrNull()

    /** L'appli a-t-elle le droit d'installer des mises à jour ? (Android 8+ : réglage à accorder une fois) */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    /** Ouvre le réglage « Installer des applis inconnues » pour cette appli. Renvoie false si la box ne le propose pas. */
    fun openInstallPermission(from: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(app.packageManager) == null) return false
        return runCatching { from.startActivity(i); true }.getOrDefault(false)
    }

    /** Télécharge et vérifie. Renvoie le fichier prêt à installer, ou lève une exception explicite. */
    fun download(r: Release, onProgress: (Int) -> Unit = {}): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "update-${r.versionCode}.apk")
        val c = (URL(r.url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = true }
        try {
            if (c.responseCode != 200) throw IllegalStateException("Téléchargement refusé (${c.responseCode})")
            val total = c.contentLength.toLong()
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input -> f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            } }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != r.sha256) { f.delete(); throw SecurityException("Fichier altéré (empreinte différente) : mise à jour refusée") }
        } finally { c.disconnect() }
        verifySignature(f)
        return f
    }

    /**
     * Même nom d'appli et MÊME signature que l'appli installée.
     * Sur certaines versions d'Android (TV en Android 10…), la lecture des certificats d'un fichier APK
     * non installé renvoie une liste vide : dans ce cas on s'appuie sur Android lui-même, qui refuse
     * toujours une mise à jour signée avec une autre clé. On ne refuse que si les deux signatures sont
     * lisibles ET différentes.
     */
    private fun verifySignature(f: File) {
        val pm = app.packageManager
        val archive = archiveInfo(f) ?: run { f.delete(); throw SecurityException("Fichier illisible") }
        if (archive.packageName != app.packageName) { f.delete(); throw SecurityException("Ce n'est pas la bonne appli") }
        val theirs = archiveCerts(f)
        val mine = installedCerts()
        if (theirs.isNotEmpty() && mine.isNotEmpty() && theirs.intersect(mine).isEmpty()) {
            f.delete(); throw SecurityException("Signature différente : mise à jour refusée")
        }
    }

    private fun archiveInfo(f: File): PackageInfo? = runCatching { app.packageManager.getPackageArchiveInfo(f.path, 0) }.getOrNull()

    private fun archiveCerts(f: File): Set<String> {
        val pm = app.packageManager
        val out = HashSet<String>()
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)?.let { out += certs(it) }
        }
        if (out.isEmpty()) runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNATURES)?.signatures?.forEach { out += sha(it.toByteArray()) }
        }
        return out
    }

    private fun installedCerts(): Set<String> {
        val pm = app.packageManager
        val out = HashSet<String>()
        if (Build.VERSION.SDK_INT >= 28) runCatching { out += certs(pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)) }
        if (out.isEmpty()) runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures?.forEach { out += sha(it.toByteArray()) }
        }
        return out
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun certs(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { sha(it.toByteArray()) }.toSet()
    }

    /** Lance l'installation : Android affiche sa fenêtre « Installer » (confirmation de l'utilisateur). */
    fun install(f: File) {
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            f.inputStream().use { input -> s.openWrite("update.apk", 0, f.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java), flags)
            s.commit(status.intentSender)
        }
    }

    private fun httpText(u: String): String {
        val c = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000; setRequestProperty("Cache-Control", "no-cache") }
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
}

/** Reçoit l'état de l'installation : affiche la confirmation Android quand elle est demandée. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> context.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
                .putString("lastError", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation annulée").apply()
        }
    }
}

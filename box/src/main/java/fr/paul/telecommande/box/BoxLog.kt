package fr.paul.telecommande.box

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Journal de la TV : ce que le service voit et décide (appli ouverte, textes lus, clics, classification, blocages).
 * Reste sur la box ; le téléphone appairé le récupère seulement quand on exporte son journal. Rien de tapé n'y figure.
 */
object BoxLog {
    private const val MAX_BYTES = 1_000_000L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "boxlog").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.FRANCE)
    @Volatile private var dir: File? = null
    @Volatile private var last = ""

    fun init(c: Context) {
        if (dir != null) return
        dir = c.applicationContext.filesDir
        val v = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()
        log("TV", "Démarrage · appli Box $v · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { append(line("CRASH", "Thread ${t.name} : " + android.util.Log.getStackTraceString(e))) }
            // Fil secondaire (réseau, minuterie…) : seul ce fil s'arrête, la protection continue.
            // Un plantage tue sinon toute l'appli, et après deux plantages en une minute Android
            // ne relance plus le service de protection tant que personne n'ouvre l'appli (vu au stress test du 28/11).
            if (t !== android.os.Looper.getMainLooper().thread && survivre()) return@setDefaultUncaughtExceptionHandler
            previous?.uncaughtException(t, e)
        }
        gardeFilPrincipal()
    }

    // ---------- Survie aux erreurs ----------
    private val erreurs = ArrayDeque<Long>()
    /** Au plus 20 erreurs rattrapées par minute : au-delà, l'appli est vraiment cassée et on laisse Android la relancer. */
    @Synchronized private fun survivre(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        while (erreurs.isNotEmpty() && now - erreurs.first() > 60_000) erreurs.removeFirst()
        erreurs.addLast(now)
        return erreurs.size <= 20
    }

    /** Nombre d'erreurs rattrapées depuis le démarrage (statut et banc de test). */
    @Volatile var rattrapees = 0; private set

    /**
     * Fil principal : une erreur pendant le traitement d'un message (évènement d'écran, minuterie…) est notée
     * puis la boucle repart, au lieu d'arrêter l'appli et donc la protection.
     */
    private fun gardeFilPrincipal() {
        if (Build.FINGERPRINT == "robolectric") return   // tests sur ordinateur : la boucle est pilotée par le test
        val main = android.os.Looper.getMainLooper()
        android.os.Handler(main).post {
            while (true) {
                try { android.os.Looper.loop(); return@post }
                catch (e: Throwable) {
                    rattrapees++
                    runCatching { append(line("CRASH", "Erreur rattrapée (protection maintenue) : " + android.util.Log.getStackTraceString(e).take(4_000))) }
                    if (!survivre()) throw e
                }
            }
        }
    }

    private fun cur() = dir?.let { File(it, "box.log") }
    private fun old() = dir?.let { File(it, "box.old.log") }
    private fun line(tag: String, msg: String) = "${synchronized(fmt) { fmt.format(Date()) }} [$tag] $msg\n"

    private fun append(text: String) {
        val f = cur() ?: return
        if (f.length() > MAX_BYTES) { old()?.delete(); f.renameTo(old()!!) }
        FileWriter(cur()!!, true).use { it.write(text) }
    }

    fun log(tag: String, msg: String) {
        val text = line(tag, msg.take(2_000))
        io.execute { runCatching { append(text) } }
    }

    /** N'écrit que si le message change (états répétés toutes les 4 s). */
    fun change(tag: String, msg: String) { if (msg != last) { last = msg; log(tag, msg) } }

    /** Les derniers [max] octets du journal, pour le téléphone. */
    fun tail(max: Int = 300_000): String {
        val text = io.submit<String> {
            (old()?.takeIf { it.exists() }?.readText() ?: "") + (cur()?.takeIf { it.exists() }?.readText() ?: "")
        }.get()
        return if (text.length > max) text.substring(text.length - max).substringAfter('\n') else text
    }
}

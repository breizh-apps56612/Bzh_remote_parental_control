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
            previous?.uncaughtException(t, e)
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

package fr.paul.telecommande.box

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/** Journal local des sessions par appli, sur la box. Rien n'est envoyé ailleurs qu'aux téléphones appairés. */
class UsageLog private constructor(context: Context) : SQLiteOpenHelper(context, "box_usage.db", null, 3) {

    data class Session(val pkg: String, val start: Long, val end: Long, val profile: String = "")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE s (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, imported INTEGER NOT NULL DEFAULT 0, profile TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX s_end ON s(end)")
        createPrograms(db)
    }

    private fun createPrograms(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS p (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT NOT NULL DEFAULT '', " +
            "profile TEXT NOT NULL DEFAULT '', start INTEGER NOT NULL, end INTEGER NOT NULL, rating TEXT NOT NULL DEFAULT '', blocked INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS p_end ON p(end)")
    }

    data class Program(val pkg: String, val title: String, val subtitle: String, val profile: String, val ms: Long, val last: Long, val rating: String, val blocked: Boolean)

    @Synchronized fun openProgram(pkg: String, title: String, subtitle: String, profile: String, at: Long): Long =
        writableDatabase.insert("p", null, ContentValues().apply { put("pkg", pkg); put("title", title.take(200)); put("subtitle", subtitle.take(200)); put("profile", profile); put("start", at); put("end", at) })

    @Synchronized fun extendProgram(id: Long, end: Long, rating: String?, blocked: Boolean) {
        writableDatabase.update("p", ContentValues().apply { put("end", end); if (rating != null) put("rating", rating); if (blocked) put("blocked", 1) }, "id=?", arrayOf(id.toString()))
    }

    /** Programmes regardés depuis [from], regroupés par titre, du plus récent au plus ancien. */
    @Synchronized fun programs(from: Long, limit: Int = 60): List<Program> {
        val out = mutableListOf<Program>()
        readableDatabase.rawQuery("SELECT pkg,title,MAX(subtitle),profile,SUM(end-start),MAX(end),MAX(rating),MAX(blocked) FROM p WHERE end>? " +
            "GROUP BY pkg,title,profile ORDER BY MAX(end) DESC LIMIT ?", arrayOf(from.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) out += Program(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3), c.getLong(4), c.getLong(5), c.getString(6) ?: "", c.getInt(7) == 1)
        }
        return out
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) db.execSQL("ALTER TABLE s ADD COLUMN profile TEXT NOT NULL DEFAULT ''")
        if (o < 3) createPrograms(db)
    }

    @Synchronized fun open(pkg: String, at: Long, profile: String = ""): Long =
        writableDatabase.insert("s", null, ContentValues().apply { put("pkg", pkg); put("start", at); put("end", at); put("profile", profile) })

    @Synchronized fun extend(id: Long, end: Long) {
        writableDatabase.update("s", ContentValues().apply { put("end", end) }, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun sessions(from: Long, to: Long): List<Session> {
        val out = mutableListOf<Session>()
        readableDatabase.rawQuery("SELECT pkg,start,end,profile FROM s WHERE end>? AND start<? ORDER BY start", arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += Session(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "")
        }
        return out
    }

    @Synchronized fun first(): Long? = readableDatabase.rawQuery("SELECT MIN(start) FROM s", null).use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
    }

    @Synchronized fun purgeBefore(t: Long) { writableDatabase.delete("s", "end<?", arrayOf(t.toString())) }

    fun totals(from: Long, to: Long): Map<String, Long> = Stats.totals(sessions(from, to), from, to)

    /** Temps du jour d'un seul profil (null = tous). */
    fun totals(from: Long, to: Long, profile: String?): Map<String, Long> =
        Stats.totals(sessions(from, to).filter { profile == null || it.profile == profile }, from, to)

    /**
     * Facultatif : si la box permet l'« accès aux données d'utilisation », on importe l'historique
     * des jours précédant l'installation (Android le conserve quelques jours).
     */
    @Synchronized fun importHistory(ctx: Context, exempt: Set<String>): Int {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0
        val firstOwn = first() ?: System.currentTimeMillis()
        val from = firstOwn - 10L * 24 * 3600 * 1000
        val events = runCatching { usm.queryEvents(from, firstOwn) }.getOrNull() ?: return 0
        val e = UsageEvents.Event()
        var cur: String? = null; var start = 0L; var n = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (cur != null && e.timeStamp > start) { insertImported(db, cur, start, e.timeStamp); n++ }
                        cur = if (e.packageName in exempt) null else e.packageName; start = e.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND, 16 /* SCREEN_NON_INTERACTIVE */ -> {
                        if (cur != null && (e.packageName == cur || e.eventType == 16) && e.timeStamp > start) { insertImported(db, cur, start, e.timeStamp); n++ }
                        cur = null
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    private fun insertImported(db: SQLiteDatabase, pkg: String, s: Long, e: Long) {
        if (e - s < 5_000 || e - s > 12 * 3600_000L) return
        db.insert("s", null, ContentValues().apply { put("pkg", pkg); put("start", s); put("end", e); put("imported", 1) })
    }

    companion object {
        @Volatile private var inst: UsageLog? = null
        fun get(c: Context) = inst ?: synchronized(this) { inst ?: UsageLog(c.applicationContext).also { inst = it } }
    }
}

object Stats {
    fun totals(sessions: List<UsageLog.Session>, from: Long, to: Long): Map<String, Long> {
        val m = HashMap<String, Long>()
        sessions.forEach { s -> val d = minOf(s.end, to) - maxOf(s.start, from); if (d > 0) m[s.pkg] = (m[s.pkg] ?: 0L) + d }
        return m
    }

    /** Répartition sur 24 heures (ms par heure) : les « plages d'utilisation ». */
    fun hourly(sessions: List<UsageLog.Session>, dayStart: Long, days: Int): LongArray {
        val out = LongArray(24)
        for (d in 0 until days) {
            val base = addDays(dayStart, d)
            for (h in 0 until 24) {
                val hs = base + h * 3600_000L; val he = hs + 3600_000L
                sessions.forEach { s -> val x = minOf(s.end, he) - maxOf(s.start, hs); if (x > 0) out[h] += x }
            }
        }
        return out
    }

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun addDays(day: Long, n: Int): Long = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_YEAR, n) }.timeInMillis

    fun minuteOfDay(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** 0 = lundi … 6 = dimanche */
    fun dayOfWeek(t: Long) = (Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_WEEK) + 5) % 7
}

package fr.paul.telecommande.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class UsageSession(val id: Long, val deviceId: String, val pkg: String, val start: Long, val end: Long)

/** Journal local des sessions de visionnage (aucune donnée ne quitte le téléphone). */
class UsageDb private constructor(context: Context) : SQLiteOpenHelper(context, "usage.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL, pkg TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_sessions ON sessions(device, end)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun open(deviceId: String, pkg: String, at: Long): Long =
        writableDatabase.insert("sessions", null, ContentValues().apply {
            put("device", deviceId); put("pkg", pkg); put("start", at); put("end", at)
        })

    @Synchronized
    fun extend(id: Long, end: Long) {
        writableDatabase.update("sessions", ContentValues().apply { put("end", end) }, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun sessions(deviceId: String, from: Long, to: Long): List<UsageSession> {
        val out = mutableListOf<UsageSession>()
        readableDatabase.rawQuery(
            "SELECT id, pkg, start, end FROM sessions WHERE device = ? AND end > ? AND start < ? ORDER BY start",
            arrayOf(deviceId, from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out += UsageSession(c.getLong(0), deviceId, c.getString(1), c.getLong(2), c.getLong(3)) }
        return out
    }

    @Synchronized
    fun firstRecord(deviceId: String): Long? =
        readableDatabase.rawQuery("SELECT MIN(start) FROM sessions WHERE device = ?", arrayOf(deviceId)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    @Synchronized
    fun purgeBefore(time: Long) { writableDatabase.delete("sessions", "end < ?", arrayOf(time.toString())) }

    /** Durée (ms) par appli entre deux instants, sessions découpées aux bornes. */
    fun totalsByApp(deviceId: String, from: Long, to: Long): Map<String, Long> =
        Usage.totals(sessions(deviceId, from, to), from, to)

    companion object {
        @Volatile private var instance: UsageDb? = null
        fun get(context: Context): UsageDb =
            instance ?: synchronized(this) { instance ?: UsageDb(context.applicationContext).also { instance = it } }
    }
}

/** Calculs purs (testables) sur les sessions. */
object Usage {
    fun totals(sessions: List<UsageSession>, from: Long, to: Long): Map<String, Long> {
        val m = HashMap<String, Long>()
        for (s in sessions) {
            val d = minOf(s.end, to) - maxOf(s.start, from)
            if (d > 0) m[s.pkg] = (m[s.pkg] ?: 0L) + d
        }
        return m
    }

    fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun addDays(dayStart: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis

    fun minuteOfDay(time: Long): Int = Calendar.getInstance().apply { timeInMillis = time }.let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    fun format(ms: Long): String {
        val min = (ms / 60_000).toInt()
        return when {
            min < 1 -> "< 1 min"
            min < 60 -> "$min min"
            min % 60 == 0 -> "${min / 60} h"
            else -> "${min / 60} h ${"%02d".format(min % 60)}"
        }
    }
}

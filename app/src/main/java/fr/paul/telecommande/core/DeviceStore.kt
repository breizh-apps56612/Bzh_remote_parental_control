package fr.paul.telecommande.core

import android.content.Context
import org.json.JSONArray

/** Persistance simple des appareils dans les SharedPreferences (JSON). */
class DeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("devices", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): MutableList<Device> {
        val raw = prefs.getString("list", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = mutableListOf<Device>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { Device.fromJson(it) }?.let { out += it }
        }
        return out
    }

    fun get(id: String): Device? = all().firstOrNull { it.id == id }

    @Synchronized
    fun save(device: Device) {
        val list = all()
        val idx = list.indexOfFirst { it.id == device.id }
        if (idx >= 0) list[idx] = device else list += device
        write(list)
    }

    @Synchronized
    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(list: List<Device>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}

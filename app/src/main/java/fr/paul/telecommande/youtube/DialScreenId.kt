package fr.paul.telecommande.youtube

import android.content.Context
import android.net.wifi.WifiManager
import fr.paul.telecommande.drivers.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Association YouTube sans code : via DIAL (protocole de « cast » du réseau local), la TV
 * expose l'identifiant d'écran de son appli YouTube. Toutes les box ne le font pas :
 * dans ce cas on passe par le code TV.
 */
object DialScreenId {
    suspend fun find(context: Context, host: String, timeoutMs: Long = 3000): String? = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("dial")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            val location = search(host, timeoutMs) ?: return@withContext null
            val appUrl = Net.client.newCall(Request.Builder().url(location).build()).execute().use { it.header("Application-URL") }
                ?: return@withContext null
            val xml = Net.client.newCall(Request.Builder().url(appUrl.trimEnd('/') + "/YouTube").build()).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            } ?: return@withContext null
            Regex("<screenId>([^<]+)</screenId>").find(xml)?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun search(host: String, timeoutMs: Long): String? {
        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
            "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n"
        DatagramSocket().use { sock ->
            sock.soTimeout = 500
            val data = msg.toByteArray()
            sock.send(DatagramPacket(data, data.size, InetAddress.getByName("239.255.255.250"), 1900))
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    if (p.address.hostAddress != host) continue
                    val text = String(p.data, 0, p.length)
                    text.lineSequence().firstOrNull { it.startsWith("LOCATION:", true) }
                        ?.substringAfter(':')?.trim()?.let { return it }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return null
    }
}

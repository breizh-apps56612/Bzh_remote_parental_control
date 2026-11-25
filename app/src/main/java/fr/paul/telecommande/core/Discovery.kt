package fr.paul.telecommande.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.resume

data class Found(val host: String, val type: DeviceType, val name: String)

/** Recherche des appareils du réseau local : SSDP/UPnP + mDNS (Android TV Remote). */
class Discovery(private val context: Context) {

    suspend fun scan(durationMs: Long = 4000): List<Found> = coroutineScope {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("telecommande-scan")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            val ssdp = async(Dispatchers.IO) { runCatching { ssdp(durationMs) }.getOrDefault(emptyList()) }
            val mdns = async { runCatching { mdns(durationMs) }.getOrDefault(emptyList()) }
            val all = mdns.await() + ssdp.await()
            // un même hôte peut répondre plusieurs fois : on garde la meilleure identification
            all.groupBy { it.host }.map { (_, list) -> list.first() }.sortedBy { it.name }
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun ssdp(durationMs: Long): List<Found> {
        val results = LinkedHashMap<String, Found>()
        val group = InetAddress.getByName("239.255.255.250")
        DatagramSocket().use { sock ->
            sock.soTimeout = 500
            val targets = listOf("ssdp:all", "roku:ecp", "urn:dial-multiscreen-org:service:dial:1",
                "urn:samsung.com:device:RemoteControlReceiver:1", "urn:lge-com:service:webos-second-screen:1")
            for (st in targets) {
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                val bytes = msg.toByteArray()
                sock.send(DatagramPacket(bytes, bytes.size, group, 1900))
            }
            val end = System.currentTimeMillis() + durationMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val text = String(p.data, 0, p.length)
                    val host = p.address.hostAddress ?: continue
                    val type = guess(text) ?: continue
                    val existing = results[host]
                    if (existing == null) results[host] = Found(host, type, "${type.label.substringBefore(" (")} – $host")
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return results.values.toList()
    }

    private fun guess(r: String): DeviceType? {
        val t = r.lowercase()
        return when {
            "roku" in t -> DeviceType.ROKU
            "samsung" in t || "tizen" in t -> DeviceType.SAMSUNG
            "webos" in t || "lge" in t -> DeviceType.LG
            "freebox" in t -> DeviceType.FREEBOX
            "kodi" in t || "xbmc" in t || "libreelec" in t -> DeviceType.KODI
            "panasonic" in t || "viera" in t -> DeviceType.PANASONIC
            "bravia" in t || "sony" in t -> DeviceType.ANDROID_TV
            "android" in t || "google" in t || "chromecast" in t -> DeviceType.ANDROID_TV
            "philips" in t -> DeviceType.PHILIPS
            else -> null
        }
    }

    /** Préfère l'adresse IPv4 (les adresses IPv6 locales posent problème à certaines box). */
    private fun ipv4Of(info: NsdServiceInfo): String? {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress?.let { return it }
        }
        val h = info.host ?: return null
        return if (h is java.net.Inet4Address) h.hostAddress else null
    }

    /** Recherche uniquement les box Android TV (mDNS), pour retrouver une box dont l'IP a changé. */
    suspend fun androidTvs(durationMs: Long = 3500): List<Found> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("telecommande-atv")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        return try { runCatching { mdns(durationMs) }.getOrDefault(emptyList()) } finally { runCatching { lock?.release() } }
    }

    private suspend fun mdns(durationMs: Long): List<Found> {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val services = mutableListOf<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { synchronized(services) { services += info } }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        withContext(Dispatchers.Main) {
            runCatching { nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
        }
        delay(durationMs)
        withContext(Dispatchers.Main) { runCatching { nsd.stopServiceDiscovery(listener) } }
        val found = mutableListOf<Found>()
        val snapshot = synchronized(services) { services.toList() }
        for (s in snapshot.distinctBy { it.serviceName }) {
            // NsdManager ne résout qu'un service à la fois : résolution séquentielle
            val resolved = withTimeoutOrNull(3000) {
                suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    runCatching {
                        nsd.resolveService(s, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                            override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) }
                        })
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            val host = resolved?.let { ipv4Of(it) ?: it.host?.hostAddress } ?: continue
            found += Found(host, DeviceType.ANDROID_TV, "${s.serviceName} (Android TV)")
        }
        return found
    }
}

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
import fr.paul.telecommande.drivers.VestelDriver
import kotlin.coroutines.resume

/** Marques fabriquées par Vestel (Smart TV « Linux »), repérées dans la fiche réseau de la TV. */
private val VESTEL_BRANDS = listOf("edenwood", "vestel", "toshiba", "telefunken", "hitachi", "jvc", "finlux",
    "techwood", "regal", "smartcenter")

data class Found(val host: String, val type: DeviceType, val name: String)

/** Recherche des appareils du réseau local : SSDP/UPnP + mDNS (Android TV Remote). */
class Discovery(private val context: Context) {

    suspend fun scan(durationMs: Long = 4000): List<Found> = coroutineScope {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("telecommande-scan")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            val ssdp = async(Dispatchers.IO) { runCatching { ssdp(durationMs) }.getOrDefault(emptyList()) }
            val mdns = async { runCatching { mdns(durationMs) }.getOrDefault(emptyList()) }
            val vestel = async(Dispatchers.IO) { runCatching { vestel() }.getOrDefault(emptyList()) }
            // Vestel d'abord : l'identification par sondage est plus sûre que la devinette SSDP
            val all = vestel.await() + mdns.await() + ssdp.await()
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

    /**
     * TV Smart Vestel (Edenwood, Toshiba, Telefunken…) : elles ne se signalent pas comme Android TV.
     * 1) annonce DIAL (SSDP) → fiche de l'appareil ; 2) en secours, balayage du Wi-Fi sur les ports
     * de télécommande Vestel (56789 et 4660). Une TV n'est retenue que si elle répond comme une Vestel.
     */
    suspend fun vestel(): List<Found> = coroutineScope {
        val locations = runCatching { dialLocations(2500) }.getOrDefault(emptyMap())
        val subnet = localSubnet()
        val candidates = LinkedHashSet<String>(locations.keys)
        if (subnet != null) {
            val sem = kotlinx.coroutines.sync.Semaphore(48)
            val hits = (1..254).map { "$subnet.$it" }.map { ip ->
                async(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        if (VestelDriver.portOpen(ip, VestelDriver.DIAL_PORT, 500) || VestelDriver.portOpen(ip, VestelDriver.TCP_PORT, 500)) ip else null
                    } finally { sem.release() }
                }
            }.mapNotNull { it.await() }
            candidates += hits
        }
        val found = candidates.map { ip -> async(Dispatchers.IO) { identifyVestel(ip, locations[ip]) } }.mapNotNull { it.await() }
        DiagLog.log("RECHERCHE", "Vestel : réseau ${subnet ?: "?"}.0/24, ${locations.size} réponse(s) DIAL, " +
            "${candidates.size} candidat(s), ${found.size} TV : ${found.joinToString { it.host }}")
        found
    }

    private fun identifyVestel(ip: String, location: String?): Found? {
        val desc = location?.let { runCatching { httpGet(it) }.getOrNull() }.orEmpty()
        val low = desc.lowercase()
        val smartCenter = runCatching { httpGet("http://$ip:${VestelDriver.DIAL_PORT}/apps/SmartCenter") }.isSuccess
        val tcp = VestelDriver.portOpen(ip, VestelDriver.TCP_PORT, 800)
        val brand = VESTEL_BRANDS.firstOrNull { it in low }
        if (!smartCenter && !tcp && brand == null) return null
        val friendly = Regex("<friendlyName>([^<]+)</friendlyName>").find(desc)?.groupValues?.get(1)?.trim().orEmpty()
        val label = when {
            "edenwood" in low -> "TV Edenwood"
            brand != null && brand != "vestel" && brand != "smartcenter" -> "TV " + brand.replaceFirstChar { it.uppercase() }
            friendly.isNotBlank() -> friendly
            else -> "TV Vestel"
        }
        return Found(ip, DeviceType.VESTEL, "$label – $ip")
    }

    /** Hôte → URL de la fiche (LOCATION) des appareils qui répondent à l'annonce DIAL. */
    private fun dialLocations(durationMs: Long): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        DatagramSocket().use { sock ->
            sock.soTimeout = 400
            val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n".toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
            val end = System.currentTimeMillis() + durationMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size); sock.receive(p)
                    val host = p.address.hostAddress ?: continue
                    val loc = String(p.data, 0, p.length).lines()
                        .firstOrNull { it.startsWith("location:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: continue
                    map.putIfAbsent(host, loc)
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return map
    }

    /** Les trois premiers octets de l'adresse IPv4 du téléphone sur le Wi-Fi (ex. « 192.168.1 »). */
    private fun localSubnet(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.interfaceAddresses }
            .map { it.address }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress?.substringBeforeLast('.')
    }.getOrNull()

    private fun httpGet(url: String): String {
        val req = okhttp3.Request.Builder().url(url).header("Connection", "close").get().build()
        fr.paul.telecommande.drivers.Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.string().orEmpty().take(20_000)
        }
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

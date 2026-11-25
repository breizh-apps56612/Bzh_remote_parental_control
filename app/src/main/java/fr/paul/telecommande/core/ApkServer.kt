package fr.paul.telecommande.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Mini-serveur web temporaire sur le téléphone : met l'appli Box à disposition de la TV
 * sur le Wi-Fi de la maison, à une adresse aléatoire, pendant 15 minutes au plus.
 * Il ne sert que ce fichier, uniquement aux appareils du réseau local.
 */
class ApkServer(private val context: Context) {
    enum class State { IDLE, READY, DOWNLOADING, DONE, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    var url: String = ""
        private set

    @Volatile private var server: ServerSocket? = null
    private val token = buildString { val r = SecureRandom(); repeat(6) { append("abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)]) } }

    fun start(): String? {
        if (server != null) return url
        val ip = localIp() ?: run { _state.value = State.ERROR; return null }
        val ss = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(8765)) } }
            .recoverCatching { ServerSocket().apply { bind(InetSocketAddress(0)) } }
            .getOrElse { _state.value = State.ERROR; return null }
        server = ss
        url = "http://$ip:${ss.localPort}/$token.apk"
        _state.value = State.READY
        DiagLog.log("APK", "Serveur prêt : $url")
        // Reste ouvert tant que l'assistant est ouvert (arrêt dans onDestroy), 2 h maximum par sécurité
        val deadline = System.currentTimeMillis() + 120 * 60_000L
        thread(name = "apk-server", isDaemon = true) {
            ss.soTimeout = 2_000
            while (server === ss && System.currentTimeMillis() < deadline) {
                val s = try { ss.accept() } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { break }
                DiagLog.log("APK", "Connexion entrante de ${s.inetAddress?.hostAddress} (${s.inetAddress?.javaClass?.simpleName})")
                thread(isDaemon = true) { runCatching { serve(s) }.onFailure { DiagLog.log("APK", "Erreur d'envoi : ${it.javaClass.simpleName} ${it.message}") } }
            }
            if (server === ss) stop()
        }
        return url
    }

    /** Vérifie que le serveur répond vraiment sur l'adresse donnée à la TV (même chemin réseau que la TV). */
    suspend fun selfTest(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (url.isEmpty()) return@withContext false
        val ok = runCatching {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"; connectTimeout = 3_000; readTimeout = 3_000
            }
            val code = c.responseCode; c.disconnect(); code == 200
        }.getOrElse { DiagLog.log("APK", "Auto-test : ${it.javaClass.simpleName} ${it.message}"); false }
        DiagLog.log("APK", "Auto-test du serveur ($url) : ${if (ok) "OK" else "ÉCHEC"}")
        ok
    }

    val running get() = server != null

    fun stop() {
        if (server != null) DiagLog.log("APK", "Serveur arrêté")
        runCatching { server?.close() }
        server = null
    }

    private fun serve(s: Socket) = s.use { sock ->
        sock.soTimeout = 15_000
        // Adresse du réseau local uniquement (IPv4, ou IPv4 vue en IPv6 sur certains téléphones)
        val addr = sock.inetAddress
        val v4 = (addr as? Inet4Address) ?: (addr as? java.net.Inet6Address)?.address?.let { b ->
            if (b.size == 16 && b.take(10).all { it == 0.toByte() } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte())
                java.net.InetAddress.getByAddress(b.copyOfRange(12, 16)) as Inet4Address else null
        }
        val remote = v4
        if (remote == null || !(remote.isSiteLocalAddress || remote.isLoopbackAddress)) {
            DiagLog.log("APK", "Connexion refusée (hors réseau local) : ${addr?.hostAddress}"); return@use
        }
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val request = reader.readLine() ?: return@use
        DiagLog.log("APK", "Requête de ${remote.hostAddress} : $request")
        while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
        val out = sock.getOutputStream()
        val path = request.split(" ").getOrNull(1) ?: ""
        if (!request.startsWith("GET ") && !request.startsWith("HEAD ") || path != "/$token.apk") {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use
        }
        val size = context.assets.openFd("box.apk").use { it.length }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n" +
            "Content-Length: $size\r\nContent-Disposition: attachment; filename=\"TelecommandeBox.apk\"\r\nConnection: close\r\n\r\n").toByteArray())
        if (request.startsWith("HEAD ")) return@use
        _state.value = State.DOWNLOADING
        context.assets.open("box.apk").use { it.copyTo(out, 64 * 1024) }
        out.flush()
        _state.value = State.DONE
        DiagLog.log("APK", "Fichier envoyé en entier à ${remote.hostAddress}")
    }

    companion object {
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        fun bundled(context: Context) = runCatching { context.assets.openFd("box.apk").close(); true }.getOrDefault(false)
    }
}

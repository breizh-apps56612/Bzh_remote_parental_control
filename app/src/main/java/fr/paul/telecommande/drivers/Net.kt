package fr.paul.telecommande.drivers

import fr.paul.telecommande.core.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Net {
    /**
     * Les TV exposent des certificats auto-signés sur le réseau local : on les accepte
     * uniquement pour les connexions vers les appareils configurés par l'utilisateur.
     */
    val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val client: OkHttpClient by lazy {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun post(
        url: String, body: String, contentType: String,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> req.header(k, v) }
        execute(req.build())
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> req.header(k, v) }
        execute(req.build())
    }

    private fun execute(req: Request): String {
        try {
            client.newCall(req).execute().use { r: Response ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw RemoteException("HTTP ${r.code} : ${text.take(120)}")
                return text
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("Appareil injoignable (${req.url.host}) : ${e.message}", e)
        }
    }
}

/** Petite session WebSocket « suspend » au-dessus d'OkHttp. */
class WsSession private constructor() {
    val incoming = Channel<String>(Channel.UNLIMITED)
    @Volatile var socket: WebSocket? = null
    @Volatile var closed = false
        private set

    fun send(text: String): Boolean = socket?.send(text) ?: false

    suspend fun receive(timeoutMs: Long): String = withTimeout(timeoutMs) { incoming.receive() }

    fun close() {
        closed = true
        runCatching { socket?.close(1000, null) }
        incoming.close()
    }

    companion object {
        suspend fun open(url: String, timeoutMs: Long = 6000): WsSession {
            val s = WsSession()
            val opened = CompletableDeferred<Unit>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) { s.incoming.trySend(text) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    s.closed = true; s.incoming.close()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    s.closed = true
                    opened.completeExceptionally(RemoteException("Connexion WebSocket impossible : ${t.message}", t))
                    s.incoming.close(t)
                }
            }
            s.socket = Net.client.newWebSocket(Request.Builder().url(url).build(), listener)
            try {
                withTimeout(timeoutMs) { opened.await() }
            } catch (e: Exception) {
                s.close()
                throw if (e is RemoteException) e else RemoteException("Délai dépassé vers $url", e)
            }
            return s
        }
    }
}

/** Wake-on-LAN : allume les TV compatibles (Samsung, LG, Android TV…) quand elles sont en veille réseau. */
object WakeOnLan {
    suspend fun send(mac: String, host: String): Boolean = withContext(Dispatchers.IO) {
        val bytes = parseMac(mac) ?: return@withContext false
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 1..16) System.arraycopy(bytes, 0, packet, i * 6, 6)
        val targets = mutableListOf("255.255.255.255")
        if (host.count { it == '.' } == 3) targets += host.substringBeforeLast('.') + ".255"
        var ok = false
        DatagramSocket().use { sock ->
            sock.broadcast = true
            for (t in targets) for (port in intArrayOf(9, 7)) {
                runCatching {
                    sock.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port)); ok = true
                }
            }
        }
        ok
    }

    fun parseMac(mac: String): ByteArray? {
        val hex = mac.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

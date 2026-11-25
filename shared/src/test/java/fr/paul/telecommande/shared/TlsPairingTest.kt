package fr.paul.telecommande.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** Échange réel TLS mutuel en local : empreintes vues de part et d'autre + preuve d'appairage. */
class TlsPairingTest {
    @Test fun mutualTlsAndProof() {
        val (bk, bc) = SelfSignedCert.generate("box")
        val (pk, pc) = SelfSignedCert.generate("phone")
        val server = BoxProtocol.sslContext(bk.private, bc).serverSocketFactory.createServerSocket() as SSLServerSocket
        server.bind(InetSocketAddress("127.0.0.1", 0)); server.needClientAuth = true
        val code = "482913"
        var serverSawPhone = ""
        var verdict = false
        val t = thread {
            (server.accept() as SSLSocket).use { s ->
                s.startHandshake()
                serverSawPhone = BoxProtocol.fingerprint(s.session.peerCertificates[0] as X509Certificate)
                val req = BoxProtocol.LineReader(s.inputStream).next()!!
                verdict = BoxProtocol.safeEquals(BoxProtocol.proof(code, serverSawPhone, BoxProtocol.fingerprint(bc)), req.getString("proof"))
                BoxProtocol.write(s.outputStream, JSONObject().put("ok", verdict))
            }
        }
        val client = BoxProtocol.sslContext(pk.private, pc).socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
        client.use { s ->
            s.startHandshake()
            val boxFp = BoxProtocol.fingerprint(s.session.peerCertificates[0] as X509Certificate)
            assertEquals(BoxProtocol.fingerprint(bc), boxFp)
            BoxProtocol.write(s.outputStream, JSONObject().put("proof", BoxProtocol.proof(code, BoxProtocol.fingerprint(pc), boxFp)))
            assertTrue(BoxProtocol.LineReader(s.inputStream).next()!!.getBoolean("ok"))
        }
        t.join(5000)
        server.close()
        assertEquals(BoxProtocol.fingerprint(pc), serverSawPhone)
        assertTrue(verdict)
    }
}

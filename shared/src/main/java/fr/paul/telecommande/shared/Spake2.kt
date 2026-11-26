package fr.paul.telecommande.shared

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Appairage par mot de passe court, sans fuite hors ligne (SPAKE2, RFC 9382, groupe MODP 2048 bits de la RFC 3526).
 *
 * Pourquoi : l'ancien appairage envoyait HMAC(code, empreintes). Une fausse box placée sur le Wi-Fi recevait cette
 * preuve et retrouvait le code à 6 chiffres en moins d'une seconde (1 million d'essais hors ligne).
 * Avec SPAKE2, chaque tentative d'un intrus ne teste qu'UN seul code, en direct, contre la vraie box
 * (3 essais par code affiché) : une chance sur 333 000 au mieux.
 *
 * Les empreintes TLS des deux appareils entrent dans la clé finale : une interception (deux liaisons TLS
 * différentes) donne des clés différentes et la confirmation échoue.
 */
object Spake2 {
    /** Nombre premier sûr de 2048 bits (RFC 3526, groupe 14), générateur 2. */
    private val P = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16
    )
    private val Q = P.subtract(BigInteger.ONE).shiftRight(1)
    private val G = BigInteger.valueOf(2)
    /** M et N : éléments du groupe dont personne ne connaît le logarithme (hachage d'une étiquette, puis carré). */
    private val M = element("telecommande SPAKE2 M")
    private val N = element("telecommande SPAKE2 N")

    private fun element(label: String): BigInteger {
        val h = BigInteger(1, expand(label.toByteArray(), 320)).mod(P)
        return h.modPow(BigInteger.valueOf(2), P)
    }

    private fun expand(seed: ByteArray, len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (out.size() < len) { out.write(sha256(seed + byteArrayOf(i.toByte()))); i++ }
        return out.toByteArray().copyOf(len)
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun w(code: String) = BigInteger(1, sha256(("telecommande-pair:" + code).toByteArray())).mod(Q)

    /** Valeur publique valide : 1 < v < p-1 et dans le sous-groupe d'ordre q (sinon on refuse). */
    private fun parse(hex: String): BigInteger {
        val v = runCatching { BigInteger(hex, 16) }.getOrNull() ?: throw IllegalArgumentException("Valeur invalide")
        require(v > BigInteger.ONE && v < P.subtract(BigInteger.ONE) && v.modPow(Q, P) == BigInteger.ONE) { "Valeur invalide" }
        return v
    }

    class Session internal constructor(private val isPhone: Boolean, code: String) {
        private val w = w(code)
        private val x = BigInteger(256, SecureRandom()).mod(Q).add(BigInteger.ONE)
        /** Valeur publique à envoyer (hexadécimal). */
        val message: String = G.modPow(x, P).multiply((if (isPhone) M else N).modPow(w, P)).mod(P).toString(16)
        private var key: ByteArray? = null

        /** Reçoit la valeur de l'autre appareil et calcule la clé commune. */
        fun finish(peerHex: String, phoneFp: String, boxFp: String) {
            val peer = parse(peerHex)
            val unblind = (if (isPhone) N else M).modPow(w, P).modInverse(P)
            val k = peer.multiply(unblind).mod(P).modPow(x, P)
            val (pa, pb) = if (isPhone) message to peerHex else peerHex to message
            key = sha256(("spake2|" + phoneFp + "|" + boxFp + "|" + pa + "|" + pb + "|" + k.toString(16) + "|" + w.toString(16)).toByteArray())
        }

        private fun mac(label: String): String {
            val k = key ?: throw IllegalStateException("Échange incomplet")
            val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(k, "HmacSHA256"))
            return m.doFinal(label.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        fun phoneConfirm() = mac("confirm-phone")
        fun boxConfirm() = mac("confirm-box")
    }

    fun phone(code: String) = Session(true, code)
    fun box(code: String) = Session(false, code)
}

package fr.paul.telecommande

import fr.paul.telecommande.core.RemoteKey
import fr.paul.telecommande.drivers.AtvSecret
import fr.paul.telecommande.drivers.IrBrands
import fr.paul.telecommande.drivers.IrEncoder
import fr.paul.telecommande.drivers.Proto
import fr.paul.telecommande.drivers.ProtoWriter
import fr.paul.telecommande.shared.SelfSignedCert
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

class EncodersTest {

    /** Décode une trame NEC/Samsung (distance d'impulsion) en octets, pour vérifier l'encodage. */
    private fun decodePulseDistance(p: IntArray): List<Int> {
        val bytes = mutableListOf<Int>()
        var i = 2; var cur = 0; var n = 0
        while (i + 1 < p.size && n < 32) {
            if (p[i + 1] > 1000) cur = cur or (1 shl (n % 8))
            n++; i += 2
            if (n % 8 == 0) { bytes += cur; cur = 0 }
        }
        return bytes
    }

    @Test fun necLgPower() {
        val f = IrEncoder.nec(0x04, 0x08)
        assertEquals(38000, f.frequency)
        assertEquals(9000, f.pattern[0]); assertEquals(4500, f.pattern[1])
        assertEquals(listOf(0x04, 0xFB, 0x08, 0xF7), decodePulseDistance(f.pattern))
        assertEquals(2 + 64 + 2, f.pattern.size)
    }

    @Test fun samsungPower() {
        val f = IrEncoder.samsung(0x07, 0x02)
        assertEquals(4500, f.pattern[0])
        assertEquals(listOf(0x07, 0x07, 0x02, 0xFD), decodePulseDistance(f.pattern))
    }

    @Test fun sonyFramesAre45msAndRepeated() {
        val f = IrEncoder.sony12(1, 21)
        assertEquals(40000, f.frequency)
        assertEquals(3 * 26, f.pattern.size)
        assertEquals(45000, f.pattern.take(26).sum())
        // commande 21 = 0b0010101 LSB d'abord : 1,0,1,0,1,0,0
        val bits = (0 until 7).map { f.pattern[2 + it * 2] == 1200 }
        assertEquals(listOf(true, false, true, false, true, false, false), bits)
    }

    @Test fun rc5StartsWithMarkAndHasValidDurations() {
        val f = IrEncoder.rc5(0, 12, false)
        assertEquals(36000, f.frequency)
        assertTrue(f.pattern.size % 2 == 0)
        f.pattern.dropLast(1).forEach { assertTrue(it == 889 || it == 1778) }
        // 14 bits = 28 demi-bits ; le premier demi-bit (espace) est retiré
        assertEquals(27 * 889, f.pattern.dropLast(1).sum() + (f.pattern.last() - 90000))
    }

    @Test fun everyBrandHasCoreKeys() {
        val core = listOf(RemoteKey.POWER, RemoteKey.VOL_UP, RemoteKey.VOL_DOWN, RemoteKey.MUTE, RemoteKey.CH_UP, RemoteKey.CH_DOWN) + RemoteKey.DIGITS
        IrBrands.ALL.forEach { b -> core.forEach { k -> assertNotNull("${b.id} $k", IrBrands.frame(b, k, false)) } }
    }

    @Test fun protoRoundTrip() {
        val msg = ProtoWriter().varint(1, 2).varint(2, 200).message(10) { string(1, "atvremote"); string(2, "Tél") }.toByteArray()
        val out = ByteArrayOutputStream()
        Proto.writeDelimited(out, msg)
        val back = Proto.parse(Proto.readDelimited(out.toByteArray().inputStream()))
        assertEquals(2L, back.long(1)); assertEquals(200L, back.long(2))
        assertEquals("Tél", String(back.message(10)!!.bytes(2)!!, Charsets.UTF_8))
        // Encodage de référence protobuf : champ 1 varint 2 -> 08 02 ; champ 2 varint 200 -> 10 C8 01
        assertArrayEquals(byteArrayOf(0x08, 0x02, 0x10, 0xC8.toByte(), 0x01), msg.copyOfRange(0, 5))
    }

    @Test fun keyInjectMatchesReferenceBytes() {
        // remote_key_inject { key_code: 26, direction: SHORT } -> 52 04 08 1A 10 03
        val b = ProtoWriter().message(10) { varint(1, 26); varint(2, 3) }.toByteArray()
        assertArrayEquals(byteArrayOf(0x52, 0x04, 0x08, 0x1A, 0x10, 0x03), b)
    }

    @Test fun selfSignedCertAndSecret() {
        val (kp, cert) = SelfSignedCert.generate("atvremote")
        cert.verify(kp.public)
        cert.checkValidity()
        val (_, server) = SelfSignedCert.generate("server")
        val c = cert.publicKey as RSAPublicKey; val s = server.publicKey as RSAPublicKey

        fun strip(v: BigInteger) = v.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val md = MessageDigest.getInstance("SHA-256")
        listOf(c.modulus, c.publicExponent, s.modulus, s.publicExponent).forEach { md.update(strip(it)) }
        md.update(byteArrayOf(0x12, 0x34))
        val h = md.digest()
        val goodCode = String.format("%02X1234", h[0].toInt() and 0xFF)
        assertArrayEquals(h, AtvSecret.compute(c, s, goodCode))
        val badFirst = String.format("%02X1234", (h[0].toInt() + 1) and 0xFF)
        assertNull(AtvSecret.compute(c, s, badFirst))
        assertNull(AtvSecret.compute(c, s, "XYZ123"))
    }
}

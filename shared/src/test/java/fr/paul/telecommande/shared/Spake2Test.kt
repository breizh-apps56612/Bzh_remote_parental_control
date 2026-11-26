package fr.paul.telecommande.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Spake2Test {
    private val phoneFp = "a".repeat(64)
    private val boxFp = "b".repeat(64)

    private fun run(phoneCode: String, boxCode: String, fpSeenByBox: String = phoneFp): Pair<Spake2.Session, Spake2.Session> {
        val p = Spake2.phone(phoneCode); val b = Spake2.box(boxCode)
        b.finish(p.message, fpSeenByBox, boxFp)
        p.finish(b.message, phoneFp, boxFp)
        return p to b
    }

    @Test fun sameCodeAgrees() {
        val (p, b) = run("452273", "452273")
        assertEquals(p.phoneConfirm(), b.phoneConfirm())
        assertEquals(p.boxConfirm(), b.boxConfirm())
        assertNotEquals(p.phoneConfirm(), p.boxConfirm())
    }

    @Test fun wrongCodeFails() {
        val (p, b) = run("452273", "452274")
        assertNotEquals(p.phoneConfirm(), b.phoneConfirm())
    }

    @Test fun interceptionFails() {
        // Intrus au milieu : la box voit une autre empreinte de téléphone que la vraie
        val (p, b) = run("452273", "452273", fpSeenByBox = "c".repeat(64))
        assertNotEquals(p.phoneConfirm(), b.phoneConfirm())
    }

    @Test fun messagesAreRandom() {
        assertNotEquals(Spake2.phone("000000").message, Spake2.phone("000000").message)
    }

    @Test fun rejectsBadValues() {
        val p = Spake2.phone("123456")
        for (bad in listOf("0", "1", "zz", "-5")) {
            assertTrue(runCatching { p.finish(bad, phoneFp, boxFp) }.isFailure)
        }
    }
}

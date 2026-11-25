package fr.paul.telecommande

import fr.paul.telecommande.core.Decision
import fr.paul.telecommande.core.ParentalLogic
import fr.paul.telecommande.core.ParentalRules
import fr.paul.telecommande.core.Usage
import fr.paul.telecommande.core.UsageSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalTest {
    private val yt = "com.google.android.youtube.tvkids"
    private val min = 60_000L

    @Test fun windowAcrossMidnight() {
        assertTrue(ParentalLogic.inWindow(22 * 60, 21 * 60, 7 * 60))
        assertTrue(ParentalLogic.inWindow(6 * 60 + 59, 21 * 60, 7 * 60))
        assertFalse(ParentalLogic.inWindow(7 * 60, 21 * 60, 7 * 60))
        assertFalse(ParentalLogic.inWindow(12 * 60, 21 * 60, 7 * 60))
        assertTrue(ParentalLogic.inWindow(13 * 60, 12 * 60, 14 * 60))
        assertFalse(ParentalLogic.inWindow(13 * 60, 13 * 60, 13 * 60))
    }

    @Test fun disabledOrOffDoesNothing() {
        val r = ParentalRules(enabled = false, dailyTotalMin = 1)
        assertEquals(Decision.None, ParentalLogic.evaluate(r, 600, emptyMap(), yt, true, 99 * min).first)
        val r2 = ParentalRules(enabled = true, dailyTotalMin = 1)
        assertEquals(Decision.None, ParentalLogic.evaluate(r2, 600, emptyMap(), yt, false, 99 * min).first)
    }

    @Test fun perAppLimitSendsHomeWithWarningBefore() {
        val r = ParentalRules(enabled = true, perAppMin = mutableMapOf(yt to 45))
        val (d1, w1) = ParentalLogic.evaluate(r, 600, mapOf(yt to 41 * min), yt, true, 41 * min)
        assertEquals(Decision.None, d1)
        assertEquals("app:$yt", w1.single().key)
        val (d2, _) = ParentalLogic.evaluate(r, 600, mapOf(yt to 45 * min), yt, true, 45 * min)
        assertTrue(d2 is Decision.GoHome)
        // une autre appli n'est pas concernée
        assertEquals(Decision.None, ParentalLogic.evaluate(r, 600, mapOf(yt to 50 * min), "com.netflix.ninja", true, 50 * min).first)
    }

    @Test fun totalAndBedtimeStandby() {
        val r = ParentalRules(enabled = true, dailyTotalMin = 120, bedtimeEnabled = true, bedtimeStart = 21 * 60, bedtimeEnd = 7 * 60)
        assertTrue(ParentalLogic.evaluate(r, 22 * 60, emptyMap(), yt, true, 0).first is Decision.Standby)
        assertTrue(ParentalLogic.evaluate(r, 15 * 60, emptyMap(), yt, true, 120 * min).first is Decision.Standby)
        val (d, w) = ParentalLogic.evaluate(r, 20 * 60 + 57, emptyMap(), yt, true, 10 * min)
        assertEquals(Decision.None, d)
        assertEquals("bed", w.single().key)
    }

    @Test fun totalsClipToRange() {
        val s = listOf(
            UsageSession(1, "d", "a", 0, 100),
            UsageSession(2, "d", "b", 50, 300),
            UsageSession(3, "d", "a", 250, 400)
        )
        assertEquals(mapOf("a" to 50L + 50L, "b" to 250L), Usage.totals(s, 50, 300))
        assertEquals("1 h 05", Usage.format(65 * min))
        assertEquals("2 h", Usage.format(120 * min))
        assertEquals("< 1 min", Usage.format(30_000))
    }
}

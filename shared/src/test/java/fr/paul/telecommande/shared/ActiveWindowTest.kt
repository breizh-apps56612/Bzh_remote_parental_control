package fr.paul.telecommande.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWindowTest {
    private val r = BoxRules(enabled = true, dailyTotalMin = 60, activeFrom = 7 * 60, activeTo = 21 * 60)
    private fun at(min: Int, total: Long = 120, dow: Int = 0) = UsageContext(min, dow, 0, total * 60_000)

    @Test fun limitsApplyInsideWindow() { assertTrue(RulesEngine.evaluate(r, "YouTube", null, at(20 * 60)) is Verdict.Block) }

    @Test fun freeAfter21h() {
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(r, "YouTube", AppRule(blocked = true), at(21 * 60 + 5)))
        assertNull(RulesEngine.remaining(r, null, at(22 * 60, total = 10)))
    }

    @Test fun bedtimeStillWins() {
        val b = r.copy(bedtimeEnabled = true, bedtimeStart = 22 * 60, bedtimeEnd = 7 * 60)
        val v = RulesEngine.evaluate(b, "YouTube", null, at(23 * 60))
        assertTrue(v is Verdict.Block && v.kind == Verdict.KIND_BEDTIME)
    }

    @Test fun weekendFree() {
        val w = r.copy(activeFrom = 0, activeTo = 0, activeDays = 0x1F)
        assertTrue(RulesEngine.evaluate(w, "YouTube", null, at(15 * 60, dow = 2)) is Verdict.Block)
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(w, "YouTube", null, at(15 * 60, dow = 5)))
        assertEquals("Règles toute la journée, en semaine", RulesEngine.activeLabel(w))
        assertEquals("Règles de 07:00 à 21:00", RulesEngine.activeLabel(r))
    }

    @Test fun json() {
        val j = BoxRules.fromJson(r.copy(activeDays = 0x1F).toJson())
        assertEquals(7 * 60, j.activeFrom); assertEquals(21 * 60, j.activeTo); assertEquals(0x1F, j.activeDays)
    }
}

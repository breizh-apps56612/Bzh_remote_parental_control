package fr.paul.telecommande.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cas limites trouvés en relisant le moteur de règles (chasse aux bugs du 25/11). */
class EdgeCasesTest {
    private val min = 60_000L
    private fun ctx(minute: Int = 15 * 60, day: Int = 2, used: Long = 0, total: Long = 0, bonus: Long = 0, totalBonus: Long = 0) =
        UsageContext(minute, day, used, total, bonus, totalBonus)

    /** Le couvre-feu tombait sans prévenir : ni bandeau 5 min avant, ni sablier. */
    @Test fun bedtimeIsAnnounced() {
        val r = BoxRules(enabled = true, bedtimeEnabled = true, bedtimeStart = 20 * 60 + 30, bedtimeEnd = 7 * 60)
        val v = RulesEngine.evaluate(r, "Netflix", null, ctx(minute = 20 * 60 + 27))
        assertTrue("attendu un avertissement, obtenu $v", v is Verdict.Warn)
        assertEquals(3 * min, RulesEngine.remaining(r, null, ctx(minute = 20 * 60 + 27)))
    }

    /** Fin de la plage horaire d'une appli : même chose, coupure sans prévenir. */
    @Test fun scheduleEndIsAnnounced() {
        val rule = AppRule(from = 14 * 60, to = 18 * 60)
        val r = BoxRules(enabled = true, apps = mutableMapOf("yt" to rule))
        val v = RulesEngine.evaluate(r, "YouTube", rule, ctx(minute = 17 * 60 + 57))
        assertTrue("attendu un avertissement, obtenu $v", v is Verdict.Warn)
    }

    /** « +30 min » après une fin d'épisode qui a débordé de 10 min ne donnait que 20 min. */
    @Test fun thirtyMinutesMeansThirtyMinutesFromNow() {
        val r = BoxRules(enabled = true, dailyTotalMin = 60)
        val c = ctx(total = 70 * min)                        // 10 min de grâce consommées au-delà de la limite
        val g = RulesEngine.grants(r, "Netflix", null, c, 30 * min)
        val after = c.copy(totalBonusMs = g.getValue(Verdict.KIND_TOTAL))
        assertEquals(30 * min, RulesEngine.remaining(r, null, after))
    }
}

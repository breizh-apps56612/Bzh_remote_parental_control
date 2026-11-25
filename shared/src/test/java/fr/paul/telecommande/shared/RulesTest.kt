package fr.paul.telecommande.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val min = 60_000L
    private fun ctx(minute: Int = 15 * 60, day: Int = 2, used: Long = 0, total: Long = 0, bonus: Long = 0, totalBonus: Long = 0) =
        UsageContext(minute, day, used, total, bonus, totalBonus)

    @Test fun disabledAllowsEverything() {
        val r = BoxRules(enabled = false, apps = mutableMapOf("yt" to AppRule(blocked = true)))
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(r, "YouTube", r.apps["yt"], ctx()))
    }

    @Test fun blockedAppCannotRequest() {
        val r = BoxRules(enabled = true, apps = mutableMapOf("yt" to AppRule(blocked = true)))
        val v = RulesEngine.evaluate(r, "YouTube", r.apps["yt"], ctx()) as Verdict.Block
        assertFalse(v.canRequest)
    }

    @Test fun perAppLimitWarnThenBlockAndBonus() {
        val rule = AppRule(limitMin = 45)
        val r = BoxRules(enabled = true, apps = mutableMapOf("yt" to rule))
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(r, "YouTube", rule, ctx(used = 30 * min)))
        val w = RulesEngine.evaluate(r, "YouTube", rule, ctx(used = 42 * min)) as Verdict.Warn
        assertEquals(3 * min, w.remainingMs)
        assertTrue(RulesEngine.evaluate(r, "YouTube", rule, ctx(used = 45 * min)) is Verdict.Block)
        // 15 minutes accordées depuis le téléphone
        assertTrue(RulesEngine.evaluate(r, "YouTube", rule, ctx(used = 45 * min, bonus = 15 * min)) !is Verdict.Block)
    }

    @Test fun scheduleDaysAndHours() {
        val weekend = AppRule(days = 0x60)                       // samedi + dimanche
        val r = BoxRules(enabled = true, apps = mutableMapOf("yt" to weekend))
        assertTrue(RulesEngine.evaluate(r, "YouTube", weekend, ctx(day = 2)) is Verdict.Block)   // mercredi
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(r, "YouTube", weekend, ctx(day = 5)))
        val afternoon = AppRule(from = 16 * 60, to = 19 * 60)
        assertTrue(RulesEngine.evaluate(r, "YouTube", afternoon, ctx(minute = 10 * 60)) is Verdict.Block)
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(r, "YouTube", afternoon, ctx(minute = 17 * 60)))
        assertEquals("le week-end", RulesEngine.daysLabel(0x60))
        assertEquals("en semaine", RulesEngine.daysLabel(0x1F))
    }

    @Test fun bedtimeAcrossMidnightAndTotal() {
        val r = BoxRules(enabled = true, bedtimeEnabled = true, bedtimeStart = 21 * 60, bedtimeEnd = 7 * 60, dailyTotalMin = 120)
        assertTrue(RulesEngine.evaluate(r, "TV", null, ctx(minute = 22 * 60)) is Verdict.Block)
        assertTrue(RulesEngine.evaluate(r, "TV", null, ctx(minute = 6 * 60 + 30)) is Verdict.Block)
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(r, "TV", null, ctx(minute = 8 * 60)))
        assertTrue(RulesEngine.evaluate(r, "TV", null, ctx(total = 120 * min)) is Verdict.Block)
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(r, "TV", null, ctx(total = 120 * min, totalBonus = 30 * min)).let {
            if (it is Verdict.Warn) Verdict.Allowed else it
        })
    }

    @Test fun rulesJsonRoundTrip() {
        val r = BoxRules(enabled = true, dailyTotalMin = 90, apps = mutableMapOf("a" to AppRule(30, 0x1F, 600, 1080), "b" to AppRule()),
            pinHash = BoxProtocol.pinHash("1234"), version = 7)
        val back = BoxRules.fromJson(org.json.JSONObject(r.toJson().toString()))
        assertEquals(r.copy(apps = mutableMapOf("a" to AppRule(30, 0x1F, 600, 1080))), back)   // règles vides non transmises
    }

    @Test fun pairingProofBindsBothCertificates() {
        val p = BoxProtocol.proof("123456", "aa", "bb")
        assertEquals(64, p.length)
        assertTrue(BoxProtocol.safeEquals(p, BoxProtocol.proof("123456", "aa", "bb")))
        assertNotEquals(p, BoxProtocol.proof("123457", "aa", "bb"))   // mauvais code
        assertNotEquals(p, BoxProtocol.proof("123456", "aa", "cc"))   // box usurpée (MITM)
        assertEquals(6, BoxProtocol.newPairingCode().length)
    }
}

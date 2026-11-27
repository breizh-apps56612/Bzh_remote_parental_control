package fr.paul.telecommande.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    private val base = BoxRules(enabled = true, dailyTotalMin = 60,
        profiles = mutableListOf(Profile("n", "Léo", "🦖", 45), Profile("p", "Parents", "👑", unlimited = true), Profile("x", "Invité")))
    private fun ctx(total: Long, used: Long = 0) = UsageContext(600, 0, used * 60_000, total * 60_000)

    @Test fun profileOverridesDailyTotal() {
        val r = base.forProfile(base.profiles[0])
        assertEquals(45, r.dailyTotalMin)
        assertTrue(RulesEngine.evaluate(r, "YouTube", null, ctx(46)) is Verdict.Block)
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(base, "YouTube", null, ctx(46)))
    }

    @Test fun parentHasNoLimits() {
        val r = base.forProfile(base.profiles[1])
        assertFalse(r.enabled)
        assertEquals(Verdict.Allowed, RulesEngine.evaluate(r, "YouTube", AppRule(blocked = true), ctx(500)))
        assertNull(RulesEngine.remaining(r, null, ctx(10)))
    }

    @Test fun profileWithoutOwnLimitUsesGlobal() {
        assertEquals(60, base.forProfile(base.profiles[2]).dailyTotalMin)
    }

    @Test fun remainingIsShortest() {
        val c = ctx(total = 50, used = 10)
        assertEquals(5 * 60_000L, RulesEngine.remaining(base, AppRule(limitMin = 15), c))
        assertEquals(10 * 60_000L, RulesEngine.remaining(base, null, c))
    }

    @Test fun jsonRoundTrip() {
        val r = BoxRules.fromJson(base.copy(graceMin = 12, showTimer = false, stillThereMin = 90).toJson())
        assertEquals(3, r.profiles.size); assertEquals("🦖", r.profiles[0].emoji); assertTrue(r.profiles[1].unlimited)
        assertEquals(12, r.graceMin); assertFalse(r.showTimer); assertEquals(90, r.stillThereMin)
        assertEquals(10, BoxRules.fromJson(org.json.JSONObject()).graceMin)
    }

    @Test fun defaultProfileAtStartup() {
        assertNull(base.startProfile())                                   // par défaut : on demande « Qui regarde ? »
        val r = base.copy(defaultProfile = "n")
        assertEquals("Léo", r.startProfile()?.name)
        val back = BoxRules.fromJson(org.json.JSONObject(r.toJson().toString()))
        assertEquals("n", back.defaultProfile)
        assertNull(base.copy(defaultProfile = "supprimé").startProfile()) // profil disparu : on redemande
        assertEquals("", BoxRules.fromJson(org.json.JSONObject("{}")).defaultProfile)
    }
}

package fr.paul.telecommande.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    private val day = ContractWizard.DAY_MS
    private val now = 1_800_000_000_000L

    private fun child(age: Int = 8) = Profile("k1", "Léo", "🦊", 0, false, birthYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - age)

    @Test fun proposalFollowsAnswers() {
        val base = ContractWizard.propose(8, mapOf("school" to 2, "weekend" to 0, "evening" to 1, "end" to 2))
        assertEquals(60, base.weekMin)                 // 6-8 ans : 60 min
        assertEquals(60, base.weekendMin)              // « pareil que la semaine »
        assertEquals(20 * 60, base.bedtime)            // 20 h
        assertEquals(10, base.graceMin)                // finir l'épisode
        val little = ContractWizard.propose(8, mapOf("school" to 0, "weekend" to 2, "evening" to 0, "end" to 0))
        assertEquals(30, little.weekMin)
        assertEquals(60, little.weekendMin)
        assertEquals(19 * 60 + 30, little.bedtime)
        assertEquals(0, little.graceMin)
        assertEquals(2, ContractWizard.propose(null, mapOf("tv" to 1)).softness)   // « une récompense » : plus de souplesse
    }

    @Test fun applyThenRulesForProfileUseWeekendAndBedtime() {
        val p = child()
        val rules = BoxRules(enabled = true, dailyTotalMin = 120, profiles = mutableListOf(p))
        ContractWizard.apply(p, ContractWizard.Mix(75, 120, 20 * 60, 5, 1, 30), mapOf("tv" to 0), showChild = true, now = now)
        assertEquals(75, rules.forProfile(p, dayOfWeek = 2).dailyTotalMin)    // mercredi
        assertEquals(120, rules.forProfile(p, dayOfWeek = 5).dailyTotalMin)   // samedi
        val r = rules.forProfile(p, 2)
        assertTrue(r.bedtimeEnabled); assertEquals(20 * 60, r.bedtimeStart); assertEquals(RulesAdvisor.BEDTIME_END, r.bedtimeEnd)
        assertEquals(5, r.graceMin)
        assertEquals(now + 30 * day, p.contract!!.nextReview)
    }

    @Test fun pausedContractAndParentHaveNoLimit() {
        val p = child(); val parent = Profile("p", "Parents", "👑", 0, true)
        val rules = BoxRules(enabled = true, dailyTotalMin = 60, profiles = mutableListOf(p, parent))
        ContractWizard.apply(p, ContractWizard.Mix(60, 60, 20 * 60, 10, 1, 30), emptyMap(), false, now)
        assertTrue(rules.forProfile(p).enabled)
        p.contract!!.status = Contract.PAUSED
        assertFalse(rules.forProfile(p).enabled)
        assertFalse(rules.forProfile(parent).enabled)
    }

    @Test fun jsonRoundTripKeepsContract() {
        val p = child()
        ContractWizard.apply(p, ContractWizard.Mix(90, 150, 21 * 60, 0, 3, 14), mapOf("tv" to 2, "school" to 1), true, now)
        p.contract!!.bonuses += ContractBonus(now, 15, "Devoirs faits")
        val back = BoxRules.fromJson(JSONObject(BoxRules(profiles = mutableListOf(p)).toJson().toString())).profiles.single()
        assertEquals(90, back.dailyTotalMin); assertEquals(150, back.weekendMin); assertEquals(21 * 60, back.bedtime); assertEquals(0, back.graceMin)
        val k = back.contract!!
        assertEquals(3, k.softness); assertEquals(14, k.reviewDays); assertTrue(k.showChild)
        assertEquals(mapOf("tv" to 2, "school" to 1), k.answers)
        assertEquals("Devoirs faits", k.bonuses.single().reason)
        // Profil sans contrat (ancien téléphone) : rien ne change
        val plain = BoxRules.fromJson(JSONObject("""{"profiles":[{"id":"a","name":"A","dailyTotalMin":60}]}""")).profiles.single()
        assertNull(plain.contract); assertEquals(-1, plain.bedtime); assertEquals(0, plain.weekendMin)
    }

    @Test fun reviewAfterRepeatedBonusesThenPostponed() {
        val p = child()
        ContractWizard.apply(p, ContractWizard.Mix(60, 60, 20 * 60, 10, 1, 30), emptyMap(), false, now)
        val k = p.contract!!
        assertNull(ContractWizard.review("Léo", k, now + day))
        repeat(6) { k.bonuses += ContractBonus(now + (it + 1) * 9 * day, 15, "Devoirs faits") }
        val later = now + 55 * day
        k.nextReview = later + 10 * day   // pas encore la date : c'est la répétition qui déclenche
        val rv = ContractWizard.review("Léo", k, later)
        assertNotNull(rv)
        assertTrue(rv!!.text, rv.text.contains("6 bonus « Devoirs faits » à Léo"))
        ContractWizard.postpone(k, later)
        assertNull(ContractWizard.review("Léo", k, later + day))
    }

    @Test fun stopKeepsWeekLimitOnly() {
        val p = child()
        ContractWizard.apply(p, ContractWizard.Mix(75, 120, 20 * 60, 5, 1, 30), emptyMap(), false, now)
        ContractWizard.stop(p)
        assertNull(p.contract); assertEquals(75, p.dailyTotalMin); assertEquals(0, p.weekendMin); assertEquals(-1, p.bedtime); assertEquals(-1, p.graceMin)
    }

    @Test fun sentenceAndChildLines() {
        val p = child()
        ContractWizard.apply(p, ContractWizard.Mix(75, 120, 20 * 60, 10, 1, 30), emptyMap(), true, now)
        val s = ContractWizard.sentence("Léo", ContractWizard.mixOf(BoxRules(), p))
        assertTrue(s, s.startsWith("Léo aura 1 h 15 les jours d'école et 2 h le week-end, TV éteinte à 20 h."))
        val lines = ContractWizard.childLines(BoxRules(), p)
        assertEquals("1 h 15 les jours d'école", lines[0].second)
        assertEquals(4, lines.size)
    }
}

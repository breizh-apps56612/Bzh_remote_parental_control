package fr.paul.telecommande.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesAdvisorTest {
    private val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    private fun child(id: String, age: Int, daily: Int = 0) = Profile(id, id.replaceFirstChar { it.uppercase() }, "🙂", daily, birthYear = year - age)

    @Test fun presetsGrowWithAge() {
        val ages = listOf(4, 7, 10, 13, 16).map { RulesAdvisor.presetFor(it) }
        assertEquals(listOf(45, 60, 90, 120, 150), ages.map { it.dailyMin })
        assertTrue(ages.zipWithNext().all { (a, b) -> a.bedtime < b.bedtime })
    }

    @Test fun applyPresetSetsDailyAndBedtimeOfYoungest() {
        val r = BoxRules(profiles = mutableListOf(child("zoe", 13)))
        val leo = child("leo", 7)
        val p = RulesAdvisor.applyPreset(r, leo)
        assertNotNull(p)
        assertEquals(60, leo.dailyTotalMin)
        assertTrue(r.bedtimeEnabled)
        assertEquals(20 * 60, r.bedtimeStart)   // celui de Léo, le plus jeune
    }

    @Test fun applyPresetKeepsExistingBedtime() {
        val r = BoxRules(bedtimeEnabled = true, bedtimeStart = 21 * 60)
        RulesAdvisor.applyPreset(r, child("leo", 7))
        assertEquals(21 * 60, r.bedtimeStart)
    }

    @Test fun parentOrUnknownAgeGetsNoPreset() {
        assertNull(RulesAdvisor.applyPreset(BoxRules(), Profile("p", "Parents", unlimited = true)))
        assertNull(RulesAdvisor.applyPreset(BoxRules(), Profile("x", "X")))
    }

    @Test fun cleanRulesGiveNoConflict() {
        val r = BoxRules(enabled = true, dailyTotalMin = 90, bedtimeEnabled = true, bedtimeStart = 21 * 60, activeFrom = 7 * 60, activeTo = 21 * 60)
        assertTrue(RulesAdvisor.check(r, knownApps = emptyList()).isEmpty())
    }

    @Test fun bedtimeBeforeEndIsConflictWithTwoFixes() {
        val r = BoxRules(bedtimeEnabled = true, bedtimeStart = 20 * 60, activeFrom = 7 * 60, activeTo = 21 * 60)
        val a = RulesAdvisor.check(r, knownApps = emptyList()).first { it.id == "bedtime_before_end" }
        assertEquals(RulesAdvisor.Level.CONFLICT, a.level)
        val keep = r.copy(); a.fixes[0].applyTo(keep); assertEquals(20 * 60, keep.activeTo)
        val move = r.copy(); a.fixes[1].applyTo(move); assertEquals(21 * 60, move.bedtimeStart)
        assertTrue(RulesAdvisor.check(keep, knownApps = emptyList()).none { it.id == "bedtime_before_end" })
        assertTrue(RulesAdvisor.check(move, knownApps = emptyList()).none { it.id == "bedtime_before_end" })
    }

    @Test fun limitLongerThanWindowIsUseless() {
        val zoe = child("zoe", 12, daily = 120)
        val r = BoxRules(activeFrom = 17 * 60, activeTo = 18 * 60 + 30, profiles = mutableListOf(zoe))
        val a = RulesAdvisor.check(r, knownApps = emptyList()).first { it.id == "limit_useless_zoe" }
        assertTrue(a.message.contains("1 h 30"))
        a.fixes[0].applyTo(r)
        assertEquals(75, r.profiles[0].dailyTotalMin)
        assertTrue(RulesAdvisor.check(r, knownApps = emptyList()).none { it.id.startsWith("limit_useless") })
    }

    @Test fun noDaysIsConflict() {
        val r = BoxRules(activeDays = 0)
        val a = RulesAdvisor.check(r).first()
        assertEquals("no_days", a.id); a.fixes[0].applyTo(r); assertEquals(AppRule.ALL_DAYS, r.activeDays)
    }

    @Test fun bedtimeCoveringEverything() {
        val r = BoxRules(bedtimeEnabled = true, bedtimeStart = 17 * 60, bedtimeEnd = 9 * 60, activeFrom = 18 * 60, activeTo = 20 * 60)
        assertTrue(RulesAdvisor.check(r, knownApps = emptyList()).any { it.id == "bedtime_all" })
    }

    @Test fun programsAboveAge() {
        val leo = child("leo", 8).apply { maxAge = 16 }
        val r = BoxRules(profiles = mutableListOf(leo))
        val a = RulesAdvisor.check(r, knownApps = emptyList()).first { it.id == "age_leo" }
        a.fixes[0].applyTo(r); assertEquals(0, r.profiles[0].maxAge)
    }

    @Test fun appLimitOverTotal() {
        val r = BoxRules(dailyTotalMin = 60, apps = mutableMapOf("tv.app" to AppRule(limitMin = 90)))
        assertTrue(RulesAdvisor.check(r, knownApps = emptyList()).any { it.id == "app_over_tv.app" })
    }

    @Test fun youtubeNoticeForYoungChildrenOnly() {
        val young = BoxRules(profiles = mutableListOf(child("leo", 8)))
        val a = RulesAdvisor.check(young, knownApps = listOf(RulesAdvisor.YOUTUBE)).first { it.id == "youtube_rating" }
        a.fixes[0].applyTo(young)
        assertTrue(young.apps[RulesAdvisor.YOUTUBE]!!.blocked)
        assertFalse(RulesAdvisor.check(young, knownApps = listOf(RulesAdvisor.YOUTUBE)).any { it.id == "youtube_rating" })
        val teen = BoxRules(profiles = mutableListOf(child("zoe", 15)))
        assertFalse(RulesAdvisor.check(teen, knownApps = listOf(RulesAdvisor.YOUTUBE)).any { it.id == "youtube_rating" })
        assertFalse(RulesAdvisor.check(BoxRules(profiles = mutableListOf(child("leo", 8))), knownApps = emptyList()).any { it.id == "youtube_rating" })
    }

    @Test fun sharedBedtimeNotice() {
        val r = BoxRules(bedtimeEnabled = true, bedtimeStart = 20 * 60, profiles = mutableListOf(child("leo", 7), child("zoe", 14)))
        assertTrue(RulesAdvisor.check(r, knownApps = emptyList()).any { it.id == "shared_bedtime" })
    }

    @Test fun conflictsComeFirst() {
        val r = BoxRules(activeDays = 0, profiles = mutableListOf(child("leo", 8)))
        val l = RulesAdvisor.check(r, knownApps = listOf(RulesAdvisor.YOUTUBE))
        assertEquals(RulesAdvisor.Level.CONFLICT, l.first().level)
        assertEquals(RulesAdvisor.Level.INFO, l.last().level)
    }
}

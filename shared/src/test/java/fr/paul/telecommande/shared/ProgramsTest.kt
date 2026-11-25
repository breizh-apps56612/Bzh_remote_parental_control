package fr.paul.telecommande.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramsTest {
    @Test fun matchesWithoutCaseOrAccents() {
        assertEquals("Squid Game", ProgramFilter.match(listOf("Squid Game"), "SQUID GAME : saison 2", null))
        assertEquals("épouvante", ProgramFilter.match(listOf("épouvante"), "Film d'EPOUVANTE", ""))
    }

    @Test fun wholeWordsOnly() {
        assertNull(ProgramFilter.match(listOf("chat"), "Le château de ma mère"))
        assertEquals("chat", ProgramFilter.match(listOf("chat"), "Le Chat Potté"))
    }

    @Test fun matchesYouTubeChannelInSecondField() {
        assertEquals("MrBeast", ProgramFilter.match(listOf("MrBeast"), "I Spent 7 Days Buried Alive", "MrBeast"))
    }

    @Test fun emptyListOrTitleNeverBlocks() {
        assertNull(ProgramFilter.match(emptyList(), "Peppa Pig"))
        assertNull(ProgramFilter.match(listOf("  "), "Peppa Pig"))
        assertNull(ProgramFilter.match(listOf("Peppa"), null, ""))
    }

    @Test fun survivesJsonRoundTrip() {
        val r = BoxRules(enabled = true, blockedPrograms = mutableListOf("Squid Game", "MrBeast"))
        assertEquals(listOf("Squid Game", "MrBeast"), BoxRules.fromJson(JSONObject(r.toJson().toString())).blockedPrograms)
        assertEquals(emptyList<String>(), BoxRules.fromJson(JSONObject("{}")).blockedPrograms)
    }
}

class RatingTest {
    private fun r(vararg t: String) = RatingReader.read(t.toList())

    @Test fun readsCommonRatings() {
        assertEquals(16, r("2024 · 16+ · 2 saisons").age)
        assertEquals(12, r("Déconseillé aux moins de 12 ans").age)
        assertEquals(10, r("-10").age)
        assertEquals(17, r("TV-MA").age)
        assertEquals(18, r("PEGI 18").age)
        assertEquals(0, r("Tous publics").age)
        assertEquals(7, r("7+", "Animation").age)
    }

    @Test fun ignoresNumbersThatAreNotRatings() {
        assertNull(r("Saison 2 · Épisode 4", "45 min", "Top 10", "+10 s").age)
        assertNull(r("2+2 = 4").age)
    }

    @Test fun readsContents() {
        assertEquals(setOf("violence", "langage"), r("16+ violence, langage grossier").contents)
        assertEquals(setOf("peur"), r("Effrayant").contents)
    }

    @Test fun blocksByAgeContentAndTitle() {
        val rules = BoxRules(enabled = true, maxAge = 12, blockedContents = mutableListOf("violence"), blockedPrograms = mutableListOf("MrBeast"))
        val leo = Profile("leo", "Léo", maxAge = 7)
        assertTrue(ProgramRules.blockReason(rules, leo, "Film", null, r("10+")) != null)          // Léo : 7+ max
        assertNull(ProgramRules.blockReason(rules, null, "Film", null, r("10+")))                 // général : 12+ max
        assertTrue(ProgramRules.blockReason(rules, null, "Film", null, r("16+")) != null)
        assertTrue(ProgramRules.blockReason(rules, null, "Film", null, r("12+ violence")) != null)
        assertTrue(ProgramRules.blockReason(rules, null, "Video", "MrBeast", null) != null)
        assertNull(ProgramRules.blockReason(rules, null, "Peppa Pig", "Peppa Pig", r("Tous publics")))
    }

    @Test fun childAgeAppliesRatingAutomatically() {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val rules = BoxRules(enabled = true)
        val nine = Profile("a", "A", birthYear = year - 9)
        assertEquals(9, nine.age())
        assertTrue(ProgramRules.blockReason(rules, nine, "Film", null, r("-10")) != null)   // 9 ans : pas de « -10 »
        assertNull(ProgramRules.blockReason(rules, nine, "Film", null, r("7+")))
        assertNull(ProgramRules.blockReason(rules, Profile("p", "Parents", unlimited = true, birthYear = year - 9), "Film", null, r("18+")))
        assertEquals(year - 9, Profile.fromJson(JSONObject(nine.toJson().toString())).birthYear)
    }
}

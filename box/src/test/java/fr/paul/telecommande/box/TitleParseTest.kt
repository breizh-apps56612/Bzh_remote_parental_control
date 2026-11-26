package fr.paul.telecommande.box

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TitleParseTest {
    private fun tile(s: String) = s.split(" | ").firstNotNullOfOrNull { GuardService.TILE_TITLE.find(it)?.groupValues?.get(1)?.trim() }
    @Test fun disneyTiles() {
        assertEquals("9-1-1: Lone Star", tile("16+  2020 • Drame, Procedural | 9-1-1: Lone Star, Classé 16+, Sortie : 2020., Genre : Drame"))
        assertEquals("9-1-1: Lone Star", tile("9-1-1: Lone Star, Classé 16+, Sortie : 2020., Genre : Drame, Procedural."))
        assertEquals("Scrubs", tile("Scrubs, Hulu Original Series, Classé 14+, Sortie : 2026."))
        assertEquals("Bluey Compilations", tile("Bluey Compilations, Il reste 44 minutes, Classé 0+, Saison 1"))
        assertNull(tile("LECTURE"))
        assertEquals("9-1-1: Lone Star", GuardService.DETAILS_TITLE.find("Sur l'écran Détails de 9-1-1: Lone Star.\n")?.groupValues?.get(1))
    }
}

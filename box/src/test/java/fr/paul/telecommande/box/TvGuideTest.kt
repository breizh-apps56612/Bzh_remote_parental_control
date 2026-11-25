package fr.paul.telecommande.box

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvGuideTest {
    private fun p(vararg r: String) = TvGuide.Program("Film", "", "Chaîne", r.toList(), "")

    @Test fun readsOfficialRatings() {
        assertEquals(12, p("FR_DVB_12").age)
        assertEquals(16, p("FR_DVB_10", "FR_DVB_16").age)
        assertEquals(0, p("US_TV_G").age)
        assertEquals(14, p("US_TV_14/US_TV_V").age)
        assertNull(p().age)
    }
}

package fr.paul.telecommande.core

import androidx.test.core.app.ApplicationProvider
import fr.paul.telecommande.shared.PinThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Le code parent du téléphone ne se devine plus en essayant tous les codes : 5 essais, puis des pauses. */
@RunWith(RobolectricTestRunner::class)
class ParentPinThrottleTest {
    @Test fun wrongCodesLeadToPause() {
        val s = ParentalStore(ApplicationProvider.getApplicationContext())
        s.setPin("2580")
        repeat(4) { assertTrue(s.tryPin("0000") is PinThrottle.Result.Wrong) }
        assertTrue(s.tryPin("0000") is PinThrottle.Result.Locked)
        // même le bon code attend la fin de la pause
        assertTrue(s.tryPin("2580") is PinThrottle.Result.Locked)
    }

    @Test fun goodCodeResetsCounter() {
        val s = ParentalStore(ApplicationProvider.getApplicationContext())
        s.setPin("2580")
        repeat(3) { s.tryPin("1111") }
        assertEquals(PinThrottle.Result.Ok, s.tryPin("2580"))
        repeat(4) { assertTrue(s.tryPin("1111") is PinThrottle.Result.Wrong) }
    }
}

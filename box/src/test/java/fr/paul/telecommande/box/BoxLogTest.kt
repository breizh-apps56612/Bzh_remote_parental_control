package fr.paul.telecommande.box

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BoxLogTest {
    @Test fun journalIsReadableAndBounded() {
        BoxLog.init(ApplicationProvider.getApplicationContext())
        BoxLog.log("LECTURE", "Netflix : clic sur Lecture · classification de la page = 7+")
        BoxLog.change("PROGRAMME", "même état"); BoxLog.change("PROGRAMME", "même état")
        val t = BoxLog.tail()
        assertTrue(t, t.contains("[LECTURE] Netflix : clic sur Lecture"))
        assertTrue(t.split("même état").size == 2)       // un seul exemplaire
        repeat(3000) { BoxLog.log("ÉCRAN", "x".repeat(200)) }
        assertTrue(BoxLog.tail(50_000).length <= 50_000)
    }
}

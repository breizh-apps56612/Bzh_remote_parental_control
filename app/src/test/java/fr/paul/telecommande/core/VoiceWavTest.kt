package fr.paul.telecommande.core

import fr.paul.telecommande.drivers.VoiceRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceWavTest {
    /** En-tête WAV valide (8 kHz, 16 bits, mono) même sans enregistrement : le lecteur du téléphone doit l'accepter. */
    @Test fun wavHeader() {
        val f = java.io.File.createTempFile("voix", ".wav")
        VoiceRecorder().saveWav(f)
        val b = f.readBytes()
        assertEquals(44, b.size)
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4)); assertEquals("data", String(b, 36, 4))
        assertEquals(8000, (b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8))
        assertEquals(16, b[34].toInt())
    }
}

package fr.paul.telecommande.core

import fr.paul.telecommande.drivers.ProtoWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagLogTest {
    @Test fun readable() {
        val key = ProtoWriter().message(10) { varint(1, 23); varint(2, 3) }.toByteArray()
        assertEquals("touche={1:23 2:3}", DiagLog.proto(key))
        val link = ProtoWriter().message(90) { string(1, "market://launch?id=com.esaba.downloader") }.toByteArray()
        assertEquals("lien_appli={1:\"market://launch?id=com.esaba.downloader\"}", DiagLog.proto(link))
    }

    @Test fun typedTextIsNeverLogged() {
        val shown = ProtoWriter().message(22) { message(2) { varint(1, 86); string(2, "5198"); varint(3, 4); string(6, "Code parent") } }.toByteArray()
        val line = DiagLog.proto(shown)
        assertEquals(false, line.contains("5198"))
        assertEquals(false, line.contains("Code parent"))
        val state = ProtoWriter().message(20) { message(1) { string(12, "fr.paul.telecommande.box") } }.toByteArray()
        assertEquals(true, DiagLog.proto(state).contains("fr.paul.telecommande.box"))
    }
}

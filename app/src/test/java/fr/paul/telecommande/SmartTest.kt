package fr.paul.telecommande

import fr.paul.telecommande.core.AppCatalog
import fr.paul.telecommande.drivers.Proto
import fr.paul.telecommande.drivers.ProtoWriter
import fr.paul.telecommande.youtube.LoungeChunkParser
import fr.paul.telecommande.drivers.Resampler
import fr.paul.telecommande.youtube.SponsorBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class SmartTest {

    @Test fun loungeChunksSingleAndMultiLine() {
        val p = LoungeChunkParser()
        val a = """[[0,["c","SID123","",8]],[1,["S","GS456"]]]"""
        assertNull(p.feed("${a.length + 1}"))
        val first = p.feed(a)
        assertNotNull(first)
        assertEquals("SID123", first!!.getJSONArray(0).getJSONArray(1).getString(1))

        // bloc réparti sur plusieurs lignes, avec accents (taille annoncée potentiellement en octets)
        val l1 = """[[2,["onAdStateChange",{"adState":"2","currentTime":"3","isSkipEnabled":"true"}]],"""
        val l2 = """[3,["nowPlaying",{"videoId":"abc","title":"Été"}]]]"""
        assertNull(p.feed("${(l1 + "\n" + l2).toByteArray().size + 1}"))
        assertNull(p.feed(l1))
        val second = p.feed(l2)
        assertNotNull(second)
        assertEquals("true", second!!.getJSONArray(0).getJSONArray(1).getJSONObject(1).getString("isSkipEnabled"))
        // l'analyseur est prêt pour le bloc suivant
        assertNull(p.feed("10"))
    }

    @Test fun sponsorSegmentsParseAndNext() {
        val json = """[{"videoID":"dQw4w9WgXcQ","segments":[
            {"segment":[30.0,75.5],"UUID":"u1","category":"sponsor","actionType":"skip"},
            {"segment":[5.0,5.4],"UUID":"u0","category":"intro","actionType":"skip"},
            {"segment":[300,320],"UUID":"u2","category":"outro","actionType":"skip"}]},
            {"videoID":"other","segments":[{"segment":[1,50],"UUID":"x","category":"sponsor"}]}]"""
        val segs = SponsorBlock.parse(json, "dQw4w9WgXcQ")
        assertEquals(listOf("u1", "u2"), segs.map { it.uuid })          // < 1 s ignoré, autre vidéo ignorée
        assertEquals("u1", SponsorBlock.next(segs, 10.0)?.uuid)          // à venir
        assertEquals("u1", SponsorBlock.next(segs, 40.0)?.uuid)          // en cours
        assertEquals("u2", SponsorBlock.next(segs, 75.0)?.uuid)          // fin de segment : on ne revient pas en arrière
        assertNull(SponsorBlock.next(segs, 330.0))
        assertEquals(4, SponsorBlock.hashPrefix("dQw4w9WgXcQ").length)
    }

    @Test fun appCatalog() {
        assertEquals(AppCatalog.Kind.YOUTUBE, AppCatalog.kind("com.google.android.youtube.tv"))
        assertEquals(AppCatalog.Kind.HOME, AppCatalog.kind("com.google.android.apps.tv.launcherx"))
        assertEquals(AppCatalog.Kind.HOME, AppCatalog.kind(""))
        assertEquals(AppCatalog.Kind.LIVE_TV, AppCatalog.kind("com.google.android.tv"))
        assertEquals(AppCatalog.Kind.VIDEO, AppCatalog.kind("com.netflix.ninja"))
        assertEquals("Netflix", AppCatalog.name("com.netflix.ninja"))
        assertEquals("Monappli", AppCatalog.name("org.exemple.monappli"))
    }

    @Test fun currentAppFromImeInject() {
        // remote_ime_key_inject { app_info { counter: 7, app_package: "com.netflix.ninja" }, text_field_status { counter_field: 3 } }
        val msg = ProtoWriter().message(20) {
            message(1) { varint(1, 7); string(12, "com.netflix.ninja") }
            message(2) { varint(1, 3) }
        }.toByteArray()
        val inject = Proto.parse(msg).message(20)!!
        assertEquals("com.netflix.ninja", String(inject.message(1)!!.bytes(12)!!))
        assertEquals(7L, inject.message(1)!!.long(1))
        assertEquals(3L, inject.message(2)!!.long(1))
    }

    @Test fun resampleTo8k() {
        val in48 = ShortArray(4800) { 1000 }       // 100 ms à 48 kHz
        val out = Resampler.to8k(in48, 4800, 48000)
        assertEquals(800, out.size)                  // 100 ms à 8 kHz
        assertEquals(1000, out[0].toInt())
        val in441 = ShortArray(441) { (it % 2 * 2000).toShort() }
        assertEquals(80, Resampler.to8k(in441, 441, 44100).size)
    }

    @Test fun voicePayloadBytes() {
        // remote_voice_payload { session_id: 5, samples: [01 02] } -> FA 01 06 08 05 12 02 01 02
        val b = ProtoWriter().message(31) { varint(1, 5); bytes(2, byteArrayOf(1, 2)) }.toByteArray()
        assertEquals(listOf(0xFA, 0x01, 0x06, 0x08, 0x05, 0x12, 0x02, 0x01, 0x02), b.map { it.toInt() and 0xFF })
    }
}

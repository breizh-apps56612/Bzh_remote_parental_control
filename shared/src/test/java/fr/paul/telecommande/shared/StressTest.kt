package fr.paul.telecommande.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.random.Random

/**
 * Tests de résistance : règles tirées au hasard par centaines de milliers, réglages corrompus,
 * code parent attaqué par force brute, fin d'épisode multipliée, messages réseau géants ou cassés,
 * dizaines de téléphones connectés en même temps.
 */
class StressTest {
    private val rnd = Random(20261125)

    private fun randomRules(): BoxRules {
        val apps = mutableMapOf<String, AppRule>()
        repeat(rnd.nextInt(0, 6)) {
            apps["app$it"] = AppRule(rnd.nextInt(0, 300), rnd.nextInt(0, 128), rnd.nextInt(0, 1440), rnd.nextInt(0, 1440), rnd.nextInt(10) == 0)
        }
        return BoxRules(
            enabled = rnd.nextInt(8) != 0, dailyTotalMin = if (rnd.nextBoolean()) 0 else rnd.nextInt(1, 600),
            bedtimeEnabled = rnd.nextBoolean(), bedtimeStart = rnd.nextInt(0, 1440), bedtimeEnd = rnd.nextInt(0, 1440),
            apps = apps, graceMin = rnd.nextInt(0, 31),
            activeFrom = if (rnd.nextBoolean()) 0 else rnd.nextInt(0, 1440), activeTo = if (rnd.nextBoolean()) 0 else rnd.nextInt(0, 1440),
            activeDays = rnd.nextInt(0, 128)
        )
    }

    private fun randomCtx() = UsageContext(
        minuteOfDay = rnd.nextInt(0, 1440), dayOfWeek = rnd.nextInt(0, 7),
        usedMs = rnd.nextLong(0, 10 * 3600_000L), totalMs = rnd.nextLong(0, 14 * 3600_000L),
        bonusMs = if (rnd.nextBoolean()) 0 else rnd.nextLong(0, 4 * 3600_000L),
        totalBonusMs = if (rnd.nextBoolean()) 0 else rnd.nextLong(0, 4 * 3600_000L),
        scheduleOverride = rnd.nextInt(5) == 0, bedtimeOverride = rnd.nextInt(5) == 0
    )

    // ---------- Règles : 300 000 situations au hasard ----------

    @Test fun rulesEngineNeverLetsThroughWhatItShouldBlock() {
        repeat(300_000) {
            val rules = randomRules()
            val pkg = if (rules.apps.isEmpty() || rnd.nextInt(4) == 0) "autre" else rules.apps.keys.random(rnd)
            val rule = rules.apps[pkg]
            val c = randomCtx()
            val v = RulesEngine.evaluate(rules, pkg, rule, c)
            val left = RulesEngine.remaining(rules, rule, c)
            if (left != null) assertTrue("reste négatif", left >= 0)

            if (!rules.enabled) { assertEquals(Verdict.Allowed, v); return@repeat }
            val bed = !c.bedtimeOverride && rules.bedtimeEnabled && rules.bedtimeStart != rules.bedtimeEnd &&
                RulesEngine.inWindow(c.minuteOfDay, rules.bedtimeStart, rules.bedtimeEnd)
            if (bed) { assertTrue("couvre-feu ignoré", v is Verdict.Block && v.kind == Verdict.KIND_BEDTIME); return@repeat }
            val active = RulesEngine.rulesActive(rules, c.minuteOfDay, c.dayOfWeek)
            // Temps libre : aucune limite, seulement l'annonce du couvre-feu qui approche
            if (!active) { assertTrue("temps libre : $v", v == Verdict.Allowed || (v is Verdict.Warn && v.message.startsWith("Couvre-feu"))); return@repeat }
            if (rule?.blocked == true) { assertTrue(v is Verdict.Block && v.kind == Verdict.KIND_BLOCKED); return@repeat }
            val totalOver = rules.dailyTotalMin > 0 && c.totalMs >= rules.dailyTotalMin * 60_000L + c.totalBonusMs
            val appOver = rule != null && rule.limitMin > 0 && c.usedMs >= rule.limitMin * 60_000L + c.bonusMs
            if (totalOver || appOver) assertTrue("limite dépassée mais autorisé : $rules $c", v is Verdict.Block)
            if (v is Verdict.Allowed || v is Verdict.Warn) {
                assertFalse(totalOver); assertFalse(appOver)
                if (left != null) assertTrue(left > 0)
            }
            if (v is Verdict.Warn) assertTrue(v.remainingMs in 1..RulesEngine.WARN_MS)
        }
    }

    @Test fun windowsAcrossMidnightMatchBruteForce() {
        for (start in 0 until 1440 step 7) for (end in 0 until 1440 step 11) {
            for (m in 0 until 1440) {
                val expected = when {
                    start == end -> true
                    start < end -> m >= start && m < end
                    else -> m >= start || m < end
                }
                assertEquals("$start-$end @ $m", expected, RulesEngine.inWindow(m, start, end))
            }
        }
    }

    // ---------- Réglages corrompus ou malveillants venus du réseau ----------

    private fun junk(): Any = when (rnd.nextInt(9)) {
        0 -> rnd.nextInt()
        1 -> Int.MIN_VALUE
        2 -> Long.MAX_VALUE
        3 -> -rnd.nextInt(100_000)
        4 -> "texte"
        5 -> JSONObject.NULL
        6 -> JSONArray().put(1)
        7 -> rnd.nextDouble() * 1e12
        else -> true
    }

    @Test fun corruptedRulesNeverCrashAndStayInRange() {
        val keys = listOf("enabled", "dailyTotalMin", "bedtimeEnabled", "bedtimeStart", "bedtimeEnd", "protectSettings", "pinHash",
            "requestMinutes", "version", "graceMin", "showTimer", "stillThereMin", "activeFrom", "activeTo", "activeDays")
        repeat(20_000) {
            val o = JSONObject()
            keys.forEach { if (rnd.nextBoolean()) o.put(it, junk()) }
            val apps = JSONObject()
            repeat(rnd.nextInt(4)) { i ->
                apps.put("p$i", if (rnd.nextInt(5) == 0) junk() else JSONObject().put("limitMin", junk()).put("days", junk()).put("from", junk()).put("to", junk()).put("blocked", junk()))
            }
            if (rnd.nextBoolean()) o.put("apps", apps)
            if (rnd.nextBoolean()) o.put("profiles", JSONArray().put(JSONObject().put("id", junk()).put("dailyTotalMin", junk())).put(junk()))
            val r = BoxRules.fromJson(o)
            assertTrue(r.dailyTotalMin in 0..1440)
            listOf(r.bedtimeStart, r.bedtimeEnd, r.activeFrom, r.activeTo).forEach { assertTrue("minute $it", it in 0 until 1440) }
            assertTrue(r.activeDays in 0..127)
            assertTrue(r.graceMin in 0..30); assertTrue(r.requestMinutes in 5..120); assertTrue(r.stillThereMin in 0..600)
            r.apps.values.forEach { a -> assertTrue(a.limitMin in 0..1440); assertTrue(a.from in 0 until 1440); assertTrue(a.to in 0 until 1440); assertTrue(a.days in 0..127) }
            r.profiles.forEach { assertTrue(it.dailyTotalMin in 0..1440) }
            // Aller-retour stable, et l'évaluation ne plante jamais
            assertEquals(r.toJson().toString(), BoxRules.fromJson(JSONObject(r.toJson().toString())).toJson().toString())
            r.apps.forEach { (k, v) -> RulesEngine.evaluate(r, k, v, randomCtx()); RulesEngine.activeLabel(r); RulesEngine.daysLabel(v.days) }
        }
    }

    @Test fun profilesNeverGiveMoreThanAsked() {
        repeat(50_000) {
            val r = randomRules()
            val p = Profile("k", "Enfant", dailyTotalMin = rnd.nextInt(0, 300), unlimited = false)
            val eff = r.forProfile(p)
            assertEquals(r.enabled, eff.enabled)
            assertEquals(if (p.dailyTotalMin > 0) p.dailyTotalMin else r.dailyTotalMin, eff.dailyTotalMin)
            assertEquals(r.bedtimeEnabled, eff.bedtimeEnabled)
        }
    }

    // ---------- Code parent : force brute sur la TV ----------

    private class Disk { var fails = 0; var until = 0L }

    @Test fun pinBruteForceIsSlowedDown() {
        var now = 1_000_000_000L
        val disk = Disk()
        val secret = "4821"
        fun throttle() = PinThrottle({ disk.fails to disk.until }, { f, u -> disk.fails = f; disk.until = u }, { now })
        // Un enfant tape un code toutes les 3 secondes pendant 24 h, en redémarrant la box de temps en temps
        var tries = 0; var found = false
        val end = now + 24 * 3600_000L
        var candidate = 0
        while (now < end) {
            val t = throttle()  // nouvelle instance à chaque essai : comme un redémarrage, l'état vient du disque
            val r = t.attempt { "%04d".format(candidate) == secret }
            if (r !is PinThrottle.Result.Locked || r.fails > 0) { tries++; if (r == PinThrottle.Result.Ok) { found = true; break }; candidate++ }
            now += 3_000
        }
        assertFalse("code trouvé en 24 h ($tries essais)", found)
        assertTrue("trop d'essais possibles en 24 h : $tries", tries < 40)
    }

    @Test fun pinThrottleStepsAndReset() {
        var now = 0L
        val disk = Disk()
        val t = PinThrottle({ disk.fails to disk.until }, { f, u -> disk.fails = f; disk.until = u }, { now })
        repeat(4) { assertTrue(t.attempt { false } is PinThrottle.Result.Wrong) }
        val l1 = t.attempt { false }
        assertTrue(l1 is PinThrottle.Result.Locked && l1.ms == 60_000L)
        // Pendant la pause, même le bon code est refusé
        assertTrue(t.attempt { true } is PinThrottle.Result.Locked)
        now += 60_001
        val l2 = t.attempt { false }
        assertTrue(l2 is PinThrottle.Result.Locked && l2.ms == 120_000L)
        now += 120_001
        assertEquals(PinThrottle.Result.Ok, t.attempt { true })
        assertEquals(0, disk.fails)
        // Horloge reculée d'un an : la pause ne dépasse jamais 1 h
        repeat(20) { now += PinThrottle.MAX_LOCK_MS + 1; t.attempt { false } }
        now -= 365L * 24 * 3600_000
        assertTrue(t.lockedForMs() <= PinThrottle.MAX_LOCK_MS)
        assertEquals(PinThrottle.MAX_LOCK_MS, PinThrottle.lockFor(50))
        assertTrue(PinThrottle.message(PinThrottle.Result.Locked(90_000)).contains("2 min"))
    }

    // ---------- Fin d'épisode : une seule par jour pour le temps total ----------

    @Test fun graceCannotBeMultipliedBySwitchingApps() {
        val day = 1_764_000_000_000L
        val claimed = HashSet<String>()
        var extra = 0
        for (pkg in listOf("netflix", "youtube", "disney", "prime", "canal", "netflix")) {
            if (claimed.add(GraceKey.of(day, "lea", pkg, Verdict.KIND_TOTAL))) extra += 10
        }
        assertEquals("temps total : une seule fin d'épisode", 10, extra)
        assertNotEquals(GraceKey.of(day, "lea", "netflix", Verdict.KIND_APP), GraceKey.of(day, "lea", "youtube", Verdict.KIND_APP))
        assertNotEquals(GraceKey.of(day, "lea", "x", Verdict.KIND_TOTAL), GraceKey.of(day + 86_400_000L, "lea", "x", Verdict.KIND_TOTAL))
        assertNotEquals(GraceKey.of(day, "lea", "x", Verdict.KIND_TOTAL), GraceKey.of(day, "tom", "x", Verdict.KIND_TOTAL))
    }

    @Test fun protectedAppsAreRecognised() {
        listOf("com.android.tv.settings", "com.android.settings", "com.google.android.tv.settings", "com.freebox.settings",
            "com.droidlogic.tvsettings").forEach { assertTrue(it, ProtectedApps.isSettings(it)) }
        listOf("com.android.packageinstaller", "com.google.android.packageinstaller").forEach { assertTrue(it, ProtectedApps.isInstaller(it)) }
        listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.disney.disneyplus", "com.esaba.downloader")
            .forEach { assertFalse(it, ProtectedApps.isSettings(it) || ProtectedApps.isInstaller(it)) }
    }

    // ---------- Messages réseau ----------

    @Test fun lineReaderSurvivesGarbage() {
        // Ligne géante : refusée sans tout charger en mémoire
        val huge = ByteArray(BoxProtocol.MAX_LINE + 10) { 'a'.code.toByte() }
        try { BoxProtocol.LineReader(ByteArrayInputStream(huge)).next(); fail("ligne géante acceptée") } catch (_: IllegalStateException) {}
        // JSON cassé : exception (le serveur ferme alors la connexion)
        try { BoxProtocol.LineReader(ByteArrayInputStream("{\"cmd\":\n".toByteArray())).next(); fail() } catch (_: Exception) {}
        // Octets aléatoires : jamais de blocage ni d'erreur autre qu'une exception
        repeat(2_000) {
            val bytes = rnd.nextBytes(rnd.nextInt(0, 2_000))
            runCatching { val r = BoxProtocol.LineReader(ByteArrayInputStream(bytes)); repeat(5) { r.next() } }
        }
        // 10 000 messages d'affilée, UTF-8 et emoji compris
        val sb = StringBuilder()
        repeat(10_000) { sb.append(JSONObject().put("cmd", "message").put("title", "À table ! 🍽️ $it").toString()).append('\n') }
        val r = BoxProtocol.LineReader(ByteArrayInputStream(sb.toString().toByteArray()))
        repeat(10_000) { assertEquals("À table ! 🍽️ $it", r.next()!!.getString("title")) }
        assertEquals(null, r.next())
    }

    @Test(timeout = 120_000) fun fortyPhonesAtOnce() {
        val (bk, bc) = SelfSignedCert.generate("box")
        val server = BoxProtocol.sslContext(bk.private, bc).serverSocketFactory.createServerSocket() as SSLServerSocket
        server.bind(InetSocketAddress("127.0.0.1", 0), 100); server.needClientAuth = true
        val serverPool = Executors.newCachedThreadPool()
        val seenPhones = Collections.synchronizedSet(HashSet<String>())
        val acceptor = Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                serverPool.execute {
                    s.use {
                        runCatching {
                            it.startHandshake()
                            seenPhones += BoxProtocol.fingerprint(it.session.peerCertificates[0] as X509Certificate)
                            val r = BoxProtocol.LineReader(it.inputStream)
                            while (true) { val m = r.next() ?: break; BoxProtocol.write(it.outputStream, JSONObject().put("id", m.optString("id")).put("echo", m.optInt("n") * 2)) }
                        }
                    }
                }
            }
        }.apply { start() }

        val phones = 40; val msgs = 50
        val ok = AtomicInteger(); val done = CountDownLatch(phones)
        val pool = Executors.newFixedThreadPool(phones)
        repeat(phones) { p ->
            pool.execute {
                try {
                    val (pk, pc) = SelfSignedCert.generate("phone$p")
                    (BoxProtocol.sslContext(pk.private, pc).socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { s ->
                        s.soTimeout = 30_000; s.startHandshake()
                        val r = BoxProtocol.LineReader(s.inputStream, BoxProtocol.MAX_TRUSTED_LINE)
                        repeat(msgs) { n ->
                            BoxProtocol.write(s.outputStream, JSONObject().put("id", "$p-$n").put("n", n))
                            val res = r.next()!!
                            if (res.getString("id") == "$p-$n" && res.getInt("echo") == n * 2) ok.incrementAndGet()
                        }
                    }
                } finally { done.countDown() }
            }
        }
        assertTrue("bloqué", done.await(100, TimeUnit.SECONDS))
        server.close(); acceptor.join(2_000); pool.shutdownNow(); serverPool.shutdownNow()
        assertEquals(phones * msgs, ok.get())
        assertEquals(phones, seenPhones.size)
    }
}

class ProfileSwitchTest {
    private val lea = Profile("lea", "Léa"); private val tom = Profile("tom", "Tom"); private val papa = Profile("p", "Papa", unlimited = true)

    @org.junit.Test fun switchingChildProfileNeedsPin() {
        org.junit.Assert.assertFalse(ProfileSwitch.needsPin(null, lea, true))       // tout premier choix
        org.junit.Assert.assertFalse(ProfileSwitch.needsPin(lea, lea, true))        // même profil après la veille
        org.junit.Assert.assertTrue(ProfileSwitch.needsPin(lea, tom, true))         // Léa a fini son temps → profil de Tom : code
        org.junit.Assert.assertTrue(ProfileSwitch.needsPin(lea, papa, true))        // profil parent : code
        org.junit.Assert.assertTrue(ProfileSwitch.needsPin(papa, papa, true))       // toujours, même s'il était le dernier
        org.junit.Assert.assertFalse(ProfileSwitch.needsPin(papa, tom, true))       // parent → enfant : plus strict, libre
        org.junit.Assert.assertFalse(ProfileSwitch.needsPin(lea, tom, false))       // pas de code parent défini
    }

    @Test fun parentAuthLiftsThePause() {
        var st = 0 to 0L
        val t = PinThrottle({ st }, { f, u -> st = f to u })
        repeat(5) { t.attempt { false } }
        assertTrue(t.lockedForMs() > 0)
        t.clear()                                     // empreinte du parent
        assertEquals(0L, t.lockedForMs())
        assertEquals(PinThrottle.Result.Ok, t.attempt { true })
    }
}

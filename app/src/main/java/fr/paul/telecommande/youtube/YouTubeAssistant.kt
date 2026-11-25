package fr.paul.telecommande.youtube

import android.os.SystemClock
import fr.paul.telecommande.core.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class YtState(
    val linked: Boolean = false,
    val connected: Boolean = false,
    val screenName: String = "",
    val videoId: String = "",
    val playing: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val adPlaying: Boolean = false,
    val adsSkipped: Int = 0,
    val sponsorsSkipped: Int = 0,
    val segments: List<Segment> = emptyList(),
    val speed: Double = 1.0,
    val message: String = ""
)

/**
 * Assistant YouTube d'une box : reste connecté à la session YouTube de la TV et
 *  - appuie sur « Passer l'annonce » dès que YouTube l'autorise ;
 *  - saute les passages sponsorisés connus de SponsorBlock ;
 *  - fournit ±10 s et la vitesse de lecture à l'interface.
 */
class YouTubeAssistant(
    private val device: Device,
    deviceId: String,
    private val scope: CoroutineScope,
    private val onPaired: (screenId: String) -> Unit
) {
    private val lounge = LoungeClient(deviceId).apply { screenId = device.ytScreenId.ifBlank { null } }
    private val _state = MutableStateFlow(YtState(linked = device.ytScreenId.isNotBlank()))
    val state: StateFlow<YtState> = _state

    private var loop: Job? = null
    private var sponsorJob: Job? = null
    private var stamp = SystemClock.elapsedRealtime()
    @Volatile private var lastSkipAt = 0L

    val running get() = loop?.isActive == true

    /** Position estimée en temps réel (la TV n'envoie la position qu'aux changements d'état). */
    fun position(): Double {
        val s = _state.value
        return if (s.playing && !s.adPlaying) s.position + (SystemClock.elapsedRealtime() - stamp) / 1000.0 * s.speed else s.position
    }

    suspend fun pairWithCode(code: String) {
        lounge.pairWithCode(code)
        val id = lounge.screenId ?: return
        device.ytScreenId = id
        onPaired(id)
        _state.update { it.copy(linked = true, screenName = lounge.screenName ?: "", message = "YouTube associé") }
        restart()
    }

    suspend fun pairWithScreenId(screenId: String): Boolean {
        lounge.screenId = screenId
        if (!lounge.refreshToken()) return false
        device.ytScreenId = screenId
        onPaired(screenId)
        _state.update { it.copy(linked = true, message = "YouTube associé") }
        restart()
        return true
    }

    fun start() {
        if (running || device.ytScreenId.isBlank()) return
        loop = scope.launch {
            var backoff = 2_000L
            while (isActive) {
                try {
                    if (lounge.loungeToken == null && !lounge.refreshToken()) {
                        _state.update { it.copy(connected = false, message = "En attente de YouTube…") }
                        delay(backoff); backoff = minOf(backoff * 2, 60_000); continue
                    }
                    if (!lounge.connect(::onEvent)) {
                        _state.update { it.copy(connected = false) }
                        delay(backoff); backoff = minOf(backoff * 2, 60_000); continue
                    }
                    backoff = 2_000L
                    _state.update { it.copy(connected = true, linked = true, screenName = lounge.screenName ?: it.screenName, message = "") }
                    while (isActive && lounge.connected) lounge.subscribe(::onEvent)
                    _state.update { it.copy(connected = false) }
                } catch (e: Exception) {
                    _state.update { it.copy(connected = false, message = e.message ?: "Connexion YouTube perdue") }
                    delay(backoff); backoff = minOf(backoff * 2, 60_000)
                }
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        sponsorJob?.cancel()
        lounge.close()
        _state.update { it.copy(connected = false) }
    }

    private fun restart() { stop(); start() }

    // ---------- Événements ----------

    private fun onEvent(type: String, data: JSONObject?) {
        when (type) {
            "onStateChange", "nowPlaying" -> data?.let { updatePlayback(it) }
            "onAdStateChange", "adPlaying" -> data?.let { onAd(it) }
            "onPlaybackSpeedChanged" -> data?.optString("playbackSpeed")?.toDoubleOrNull()?.let { sp ->
                syncStamp(); _state.update { it.copy(speed = sp) }; scheduleSponsor()
            }
            "loungeScreenDisconnected" -> _state.update { it.copy(playing = false) }
        }
    }

    private fun syncStamp() {
        val pos = position()
        stamp = SystemClock.elapsedRealtime()
        _state.update { it.copy(position = pos) }
    }

    private fun updatePlayback(d: JSONObject) {
        val st = d.optString("state")
        val pos = d.optString("currentTime").toDoubleOrNull()
        val dur = d.optString("duration").toDoubleOrNull()
        val vid = d.optString("videoId")
        stamp = SystemClock.elapsedRealtime()
        val newVideo = vid.isNotBlank() && vid != _state.value.videoId
        _state.update {
            it.copy(
                playing = if (st.isNotBlank()) st == "1" else it.playing,
                position = pos ?: it.position,
                duration = dur ?: it.duration,
                videoId = if (vid.isNotBlank()) vid else it.videoId,
                adPlaying = if (st == "1") false else it.adPlaying,
                segments = if (newVideo) emptyList() else it.segments
            )
        }
        if (newVideo) loadSegments(vid) else scheduleSponsor()
    }

    private fun onAd(d: JSONObject) {
        val adState = d.optString("adState")
        val ended = adState in setOf("0", "1082") && d.optString("currentTime") != "0" && adState.isNotBlank()
        if (ended) { _state.update { it.copy(adPlaying = false) }; return }
        _state.update { it.copy(adPlaying = true, playing = false) }
        d.optString("contentVideoId").takeIf { it.isNotBlank() && it != _state.value.videoId }?.let { vid ->
            _state.update { it.copy(videoId = vid, segments = emptyList()) }
            loadSegments(vid)
        }
        if (device.ytAutoSkipAds && d.optString("isSkipEnabled") == "true") {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSkipAt < 1500) return   // les deux événements arrivent souvent ensemble
            lastSkipAt = now
            scope.launch {
                if (lounge.command("skipAd")) {
                    _state.update { it.copy(adsSkipped = it.adsSkipped + 1, adPlaying = false, message = "Publicité passée") }
                }
            }
        }
    }

    private fun loadSegments(videoId: String) {
        if (!device.ytSkipSponsors) return
        scope.launch {
            val segs = SponsorBlock.segments(videoId)
            if (_state.value.videoId == videoId) {
                _state.update { it.copy(segments = segs) }
                scheduleSponsor()
            }
        }
    }

    private fun scheduleSponsor() {
        sponsorJob?.cancel()
        val s = _state.value
        if (!device.ytSkipSponsors || !s.playing || s.adPlaying || s.segments.isEmpty()) return
        val pos = position()
        val seg = SponsorBlock.next(s.segments, pos) ?: return
        val waitMs = (((seg.start - pos).coerceAtLeast(0.0)) / s.speed * 1000).toLong()
        val videoId = s.videoId
        sponsorJob = scope.launch {
            delay(waitMs)
            val cur = _state.value
            if (!cur.playing || cur.adPlaying || cur.videoId != videoId) return@launch
            if (lounge.command("seekTo", mapOf("newTime" to "%.1f".format(java.util.Locale.US, seg.end)))) {
                _state.update { it.copy(sponsorsSkipped = it.sponsorsSkipped + 1, message = "Passage sponsorisé sauté (${label(seg.category)})") }
            }
        }
    }

    private fun label(cat: String) = when (cat) {
        "sponsor" -> "sponsor"; "selfpromo" -> "autopromo"; "interaction" -> "rappel d'abonnement"
        "intro" -> "intro"; "outro" -> "générique"; "preview" -> "aperçu"; "music_offtopic" -> "hors-musique"
        else -> cat
    }

    // ---------- Commandes de l'interface ----------

    fun seekBy(seconds: Double) = scope.launch {
        val target = (position() + seconds).coerceIn(0.0, _state.value.duration.takeIf { it > 0 } ?: Double.MAX_VALUE)
        lounge.command("seekTo", mapOf("newTime" to "%.1f".format(java.util.Locale.US, target)))
    }

    fun cycleSpeed() = scope.launch {
        val speeds = listOf(1.0, 1.25, 1.5, 1.75, 2.0)
        val next = speeds.firstOrNull { it > _state.value.speed + 0.01 } ?: 1.0
        if (lounge.command("setPlaybackSpeed", mapOf("playbackSpeed" to next.toString()))) {
            syncStamp(); _state.update { it.copy(speed = next) }
        }
    }

    fun settingsChanged() {
        if (device.ytSkipSponsors && _state.value.segments.isEmpty() && _state.value.videoId.isNotBlank()) loadSegments(_state.value.videoId)
        scheduleSponsor()
    }
}

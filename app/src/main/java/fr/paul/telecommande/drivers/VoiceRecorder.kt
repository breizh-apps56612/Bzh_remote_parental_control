package fr.paul.telecommande.drivers

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Capture le micro du téléphone et produit du PCM 16 bits mono 8 kHz (format attendu par
 * les box Android TV). Si le téléphone n'accepte pas 8 kHz, on enregistre plus haut et on
 * rééchantillonne. Fin automatique après un silence une fois qu'on a commencé à parler.
 */
class VoiceRecorder {
    @Volatile private var stopRequested = false
    @Volatile var level = 0f       // 0..1, pour l'animation du bouton
        private set

    fun stop() { stopRequested = true }

    // Bilan du dernier enregistrement (journal, et pour réécouter ce que la TV a reçu)
    var sampleRate = 0; private set
    var peakDb = -96f; private set
    var gain = 1f; private set
    var spoke = false; private set
    var durationMs = 0L; private set
    private val sent = ByteArrayOutputStream()

    /** Ce qui a été envoyé à la TV, en fichier WAV (PCM 16 bits mono 8 kHz). */
    fun saveWav(file: java.io.File) {
        val pcm = sent.toByteArray()
        java.io.DataOutputStream(file.outputStream().buffered()).use { o ->
            fun i32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
            fun i16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            o.writeBytes("RIFF"); i32(36 + pcm.size); o.writeBytes("WAVE")
            o.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(8000); i32(16000); i16(2); i16(16)
            o.writeBytes("data"); i32(pcm.size); o.write(pcm)
        }
    }

    /**
     * @param onChunk reçoit des blocs d'au moins [chunkBytes] octets (dernier bloc éventuellement plus court)
     * @param autoStopOnSilence arrête après ~1,3 s de silence suivant de la parole
     */
    @SuppressLint("MissingPermission")
    suspend fun record(
        onChunk: suspend (ByteArray) -> Unit,
        autoStopOnSilence: Boolean,
        maxMs: Long = 12_000,
        chunkBytes: Int = 8192
    ) = withContext(Dispatchers.IO) {
        stopRequested = false
        val rates = intArrayOf(8000, 16000, 44100, 48000)
        var rec: AudioRecord? = null
        var rate = 8000
        for (r in rates) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val candidate = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, r, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, r / 5 * 2))
            }.getOrNull()
            if (candidate?.state == AudioRecord.STATE_INITIALIZED) { rec = candidate; rate = r; break }
            candidate?.release()
        }
        val audio = rec ?: throw IllegalStateException("Micro indisponible")
        val out = ByteArrayOutputStream()
        sent.reset(); sampleRate = rate; spoke = false; gain = 1f
        val buf = ShortArray(rate / 20)        // blocs de 50 ms
        val start = System.currentTimeMillis()
        var silenceSince = 0L
        var maxPeak = 0
        try {
            audio.startRecording()
            while (coroutineContext.isActive && !stopRequested && System.currentTimeMillis() - start < maxMs) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                val pcm8k = Resampler.to8k(buf, n, rate)
                var peak = 0
                for (s in pcm8k) peak = maxOf(peak, abs(s.toInt()))
                level = (peak / 12000f).coerceIn(0f, 1f)
                // Gain automatique : la source « reconnaissance vocale » n'a pas de réglage de volume et sort souvent très bas
                maxPeak = maxOf(maxPeak, peak)
                gain = if (maxPeak > 400) (20000f / maxPeak).coerceIn(1f, 8f) else 4f
                for (s in pcm8k) {
                    val v = (s * gain).toInt().coerceIn(-32768, 32767)
                    out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                    sent.write(v and 0xFF); sent.write((v shr 8) and 0xFF)
                }
                if (out.size() >= chunkBytes) { onChunk(out.toByteArray()); out.reset() }
                if (autoStopOnSilence) {
                    val now = System.currentTimeMillis()
                    if (peak > 1500) { spoke = true; silenceSince = 0 }
                    else if (spoke) { if (silenceSince == 0L) silenceSince = now else if (now - silenceSince > 1300) break }
                    else if (now - start > 6000) break   // personne n'a parlé
                }
            }
            if (out.size() > 0) onChunk(out.toByteArray())
        } finally {
            durationMs = System.currentTimeMillis() - start
            peakDb = if (maxPeak > 0) (20 * kotlin.math.log10(maxPeak / 32768.0)).toFloat() else -96f
            level = 0f
            runCatching { audio.stop() }
            audio.release()
        }
    }
}

/** Rééchantillonnage simple vers 8 kHz (moyenne des échantillons, suffisant pour la voix). */
object Resampler {
    fun to8k(input: ShortArray, n: Int, rate: Int): ShortArray {
        if (rate == 8000) return input.copyOf(n)
        val outLen = (n.toLong() * 8000 / rate).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val from = (i.toLong() * rate / 8000).toInt()
            val to = minOf(n, ((i + 1).toLong() * rate / 8000).toInt()).coerceAtLeast(from + 1)
            var sum = 0
            for (j in from until to) sum += input[j]
            out[i] = (sum / (to - from)).toShort()
        }
        return out
    }
}

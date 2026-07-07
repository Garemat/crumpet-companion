package io.github.garemat.crumpet.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/** Push-to-talk capture: 16kHz mono 16-bit PCM — what the brain's Whisper wants — wrapped
 *  as WAV in memory (30s ≈ 960KB, no files). One utterance at a time; a hard cap stops a
 *  forgotten toggle from recording forever. The UI gates start() behind the RECORD_AUDIO
 *  grant. */
class VoiceRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 30
        private const val MIN_UTTERANCE_BYTES = SAMPLE_RATE   // half a second of 16-bit PCM
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val buffer = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (record != null) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,  // STT-tuned input path
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE),  // ≥0.5s of headroom so the reader never starves
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        buffer.reset()
        rec.startRecording()
        record = rec
        worker = Thread {
            val chunk = ByteArray(4096)
            val cap = SAMPLE_RATE * 2 * MAX_SECONDS
            while (record === rec && buffer.size() < cap) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(buffer) { buffer.write(chunk, 0, n) }
                else if (n < 0) break
            }
        }.apply { start() }
        return true
    }

    /** Stop and return the utterance as WAV — null for a fumbled tap too short to be speech. */
    fun stop(): ByteArray? {
        val rec = record ?: return null
        record = null   // signals the worker loop to end
        worker?.join(1000)
        worker = null
        runCatching { rec.stop() }
        rec.release()
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        buffer.reset()
        return if (pcm.size < MIN_UTTERANCE_BYTES) null else pcmToWav(pcm, SAMPLE_RATE)
    }
}

package io.github.garemat.crumpet.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/** The full-screen hands-free ear: one mic loop that feeds the on-device wake detector
 *  while Crumpet is idle, and on "hey crumpet" chirps, captures the utterance with
 *  silence endpointing, and hands the WAV to [VoiceSession]. Runs ONLY while
 *  FaceActivity is started — that's the battery deal: no background mic service, ever.
 *  The mic is muted (read-and-discard) while a turn is in flight, plus a short tail
 *  after Crumpet speaks so his own voice can't wake him (the satellites' echo guard). */
class HandsFreeLoop(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val SILENCE_MEAN_ABS = 0.015f * 32768f  // satellite SILENCE_THRESHOLD
        private const val SILENCE_STOP_MS = 1300L    // quiet this long after speech = done
        private const val SPEECH_WAIT_MS = 6000L     // woke but said nothing = stand down
        private const val UTTERANCE_CAP_MS = 15000L  // satellite UTTERANCE_TIMEOUT
        private const val ECHO_TAIL_MS = 1000L       // deaf period after Crumpet's own voice
    }

    private var thread: Thread? = null
    @Volatile private var running = false

    /** True while capturing an utterance after a wake word (the "I'm listening" UI). */
    private val _listening = MutableStateFlow(false)
    val listening = _listening.asStateFlow()

    // If hands-free can't start (mic held by another app, model load failure, missing native
    // lib on this device…) it must NEVER take the face down with it — the loop swallows every
    // throwable, disables itself, and reports the reason here so FaceActivity can surface it
    // instead of the app just vanishing.
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun start() {
        if (running) return
        running = true
        _error.value = null
        thread = Thread(::run, "crumpet-handsfree").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        _listening.value = false
    }

    /** Thread entry: the outermost guard. Any failure — model load, native lib, the mic being
     *  unavailable — is caught here, so a broken ear can only ever mean "no hands-free", never
     *  a crash. */
    private fun run() {
        try {
            loop()
        } catch (t: Throwable) {
            Log.e("HandsFree", "wake word loop stopped", t)
            _error.value = t.message?.take(120) ?: t.javaClass.simpleName
            running = false
            _listening.value = false
        }
    }

    @SuppressLint("MissingPermission")  // FaceActivity only starts the loop when granted
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("microphone unavailable")
        }
        // Build the detector only after the mic is secured, and tear BOTH down together — a
        // half-built loop (models loaded, mic dead, or vice-versa) must leak neither.
        var detector: WakeWordDetector? = null
        try {
            detector = WakeWordDetector(context)
            val chunk = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
            var mutedUntil = 0L
            record.startRecording()
            while (running) {
                // Crucial ordering: check state BEFORE reading. While a turn runs, TtsPlayer
                // holds audio focus and plays the reply; reading the mic in that window could
                // error the AudioRecord and kill the thread — the ear went dead after one wake.
                // So during a turn we don't touch the mic at all.
                if (VoiceSession.state.value != VoiceState.Idle) {
                    mutedUntil = SystemClock.elapsedRealtime() + ECHO_TAIL_MS  // echo tail after
                    detector.reset()
                    Thread.sleep(60)
                    continue
                }
                if (SystemClock.elapsedRealtime() < mutedUntil) {
                    Thread.sleep(20)  // brief deaf tail so the reply's last words can't re-wake us
                    continue
                }
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) throw IllegalStateException("microphone read error ($n)")  // -> restart
                if (n == chunk.size && detector.accept(chunk)) {
                    Log.d("VoiceTiming", "wake detected")
                    chirp()
                    val wav = capture(record, chunk)
                    if (wav != null) VoiceSession.sendWav(context, wav)
                    detector.reset()
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            detector?.close()
            _listening.value = false
        }
    }

    /** Post-wake capture with the satellite path's endpointing: wait for speech (or give
     *  up), then stop after a silence gap, hard-capped. Returns WAV, or null for a false
     *  wake that never turned into speech. */
    private fun capture(record: AudioRecord, chunk: ShortArray): ByteArray? {
        _listening.value = true
        val pcm = ByteArrayOutputStream()
        val started = SystemClock.elapsedRealtime()
        var heardSpeech = false
        var silentSince = 0L
        try {
            while (running) {
                val now = SystemClock.elapsedRealtime()
                if (now - started > UTTERANCE_CAP_MS) break
                if (!heardSpeech && now - started > SPEECH_WAIT_MS) return null
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) break
                var sum = 0L
                for (i in 0 until n) {
                    val s = chunk[i]
                    pcm.write(s.toInt() and 0xFF)
                    pcm.write((s.toInt() shr 8) and 0xFF)
                    sum += if (s >= 0) s.toLong() else -s.toLong()
                }
                val silent = (sum.toFloat() / n) < SILENCE_MEAN_ABS
                if (silent) {
                    if (heardSpeech) {
                        if (silentSince == 0L) silentSince = now
                        else if (now - silentSince > SILENCE_STOP_MS) break
                    }
                } else {
                    heardSpeech = true
                    silentSince = 0L
                }
            }
        } finally {
            _listening.value = false
        }
        if (!heardSpeech) return null
        val bytes = pcm.toByteArray()
        Log.d("VoiceTiming", "captured ${bytes.size / 32}ms speech in " +
            "${SystemClock.elapsedRealtime() - started}ms of listening")
        return pcmToWav(bytes)
    }

    private fun chirp() {
        runCatching {
            val fd = context.assets.openFd("oww/awake.wav")
            MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        }
    }
}

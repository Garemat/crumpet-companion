package io.github.garemat.crumpet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.delay

/** Plays a sentence-streamed reply (raw PCM16 mono) AS THE CHUNKS ARRIVE — the streamed
 *  counterpart of TtsPlayer, same transient-may-duck focus so music dips, car BT works.
 *  Underruns between sentences are silence, not errors: AudioTrack just waits for the
 *  next write. One stream at a time; begin() cuts off whatever was playing. */
object PcmStreamPlayer {
    private var track: AudioTrack? = null
    private var release: (() -> Unit)? = null
    private var framesWritten = 0L

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun begin(context: Context, sampleRate: Int) {
        stop()
        val app = context.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        release = { am.abandonAudioFocusRequest(focus) }
        framesWritten = 0
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        runCatching {
            am.requestAudioFocus(focus)
            track = AudioTrack(
                attrs,
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                // A second of headroom: sentence chunks land in bursts and the write()
                // below blocks when full, which self-paces the network read.
                maxOf(minBuf, sampleRate * 2),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            ).also { it.play() }
        }.onFailure { stop() }
    }

    /** Blocking write (call on an IO dispatcher) — backpressure is the point. */
    fun write(buf: ByteArray, len: Int) {
        val t = track ?: return
        var off = 0
        while (off < len) {
            val n = t.write(buf, off, len - off)
            if (n <= 0) return  // released/errored under us — the guard in drain() closes out
            off += n
        }
        framesWritten += len / 2
    }

    /** Wait until everything written has actually PLAYED, then release. Bounded by the
     *  audio's own length + a beat, so a wedged track can't hold the turn open. */
    suspend fun drain() {
        val t = track ?: return
        val total = framesWritten
        val rate = t.sampleRate.coerceAtLeast(1)
        val guardMs = total * 1000L / rate + 2_000
        val start = android.os.SystemClock.elapsedRealtime()
        runCatching {
            while (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL < total &&
                android.os.SystemClock.elapsedRealtime() - start < guardMs
            ) {
                delay(50)
            }
        }
        stop()
    }

    /** Stop playback (if any) and release the track/focus; safe to call anytime. */
    fun stop() {
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        track = null
        release?.invoke()
        release = null
    }
}

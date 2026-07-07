package io.github.garemat.crumpet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File

/** Plays Crumpet's synthesized reply on whatever owns the output right now (phone
 *  speaker, car Bluetooth), taking transient-may-duck focus so music dips instead of
 *  stopping. One reply at a time — a new play() cuts the previous one off. */
object TtsPlayer {
    private var player: MediaPlayer? = null
    private var cleanup: (() -> Unit)? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun play(context: Context, wav: ByteArray, onDone: () -> Unit) {
        stop()
        val app = context.applicationContext
        val f = File.createTempFile("tts", ".wav", app.cacheDir)
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        var finished = false
        cleanup = {
            am.abandonAudioFocusRequest(focus)
            f.delete()
            if (!finished) {
                finished = true
                onDone()
            }
        }
        runCatching {
            f.writeBytes(wav)
            am.requestAudioFocus(focus)
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(f.absolutePath)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); true }
                prepare()  // small local file — sync prepare is fine
                start()
            }
        }.onFailure { stop() }
    }

    /** Stop playback (if any) and release focus/temp file; safe to call anytime. */
    fun stop() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        cleanup?.invoke()
        cleanup = null
    }
}

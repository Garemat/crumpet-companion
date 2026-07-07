package io.github.garemat.crumpet.audio

import android.content.Context
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.net.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The PTT round-trip: Idle → (tap) Recording → (tap) Thinking → Speaking → Idle. */
enum class VoiceState { Idle, Recording, Thinking, Speaking }

/** The one PTT state machine, shared by every surface that can talk — the chat screen's
 *  mic button and the face activity's PiP action drive the SAME session, so a turn
 *  started in one place finishes cleanly wherever the user ends up. App-scoped on
 *  purpose: a voice turn must survive the activity that started it. */
object VoiceSession {
    private val recorder = VoiceRecorder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VoiceState.Idle)
    val state = _state.asStateFlow()

    // One-line feedback ("didn't catch that", transport errors) — transient by design:
    // never enters the chat thread or the persisted cache.
    private val _note = MutableStateFlow<String?>(null)
    val note = _note.asStateFlow()

    /** First tap records, second tap sends. The finished turn's chat lines arrive via the
     *  `exchange` frame (the brain doesn't skip the asker for /voice — this surface writes
     *  no chat lines itself). Callers gate this behind the RECORD_AUDIO grant. */
    fun toggle(context: Context) {
        when (_state.value) {
            VoiceState.Idle -> {
                _note.value = null
                if (recorder.start()) _state.value = VoiceState.Recording
                else _note.value = "Couldn't open the microphone."
            }
            VoiceState.Recording -> send(context.applicationContext)
            else -> {}  // mid-turn — nothing sensible to toggle
        }
    }

    private fun send(app: Context) = scope.launch {
        val wav = withContext(Dispatchers.IO) { recorder.stop() }
        if (wav == null) {
            _state.value = VoiceState.Idle  // fumbled tap — too short to be speech
            return@launch
        }
        turn(app, wav)
    }

    /** Run a turn from an already-captured WAV — the hands-free loop's entry (it does its
     *  own wake + capture, then hands the utterance here). No-op if a turn is already
     *  running, so an overlapping wake can't double-fire. Must be called with a WAV that
     *  actually holds speech; the loop's endpointing guarantees that. */
    fun sendWav(context: Context, wav: ByteArray) {
        if (_state.value != VoiceState.Idle) return
        scope.launch { turn(context.applicationContext, wav) }
    }

    private suspend fun turn(app: Context, wav: ByteArray) {
        _state.value = VoiceState.Thinking
        val (base, tok, _) = Prefs(app).config()
        val result = runCatching {
            withContext(Dispatchers.IO) { Net.voice(base, tok, wav) }
        }.getOrElse { e ->
            _note.value = "Voice failed: ${e.message?.take(80) ?: "can't reach the brain"}"
            _state.value = VoiceState.Idle
            return
        }
        if (!result.ok) {
            _note.value = "Didn't catch that — try again?"
            _state.value = VoiceState.Idle
            return
        }
        val tts = result.ttsId?.let { id ->
            withContext(Dispatchers.IO) { runCatching { Net.fetchTts(base, tok, id) }.getOrNull() }
        }
        if (tts == null) {
            _state.value = VoiceState.Idle  // reply text still lands via the exchange frame
            return
        }
        _state.value = VoiceState.Speaking
        TtsPlayer.play(app, tts) { _state.value = VoiceState.Idle }
    }
}

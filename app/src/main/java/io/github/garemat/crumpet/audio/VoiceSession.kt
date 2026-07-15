package io.github.garemat.crumpet.audio

import android.content.Context
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.net.Net
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    // The in-flight turn, so interrupt() can cancel speech that hasn't started yet.
    @Volatile
    private var turnJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(VoiceState.Idle)
    val state = _state.asStateFlow()

    // One-line feedback ("didn't catch that", transport errors) — transient by design:
    // never enters the chat thread or the persisted cache.
    private val _note = MutableStateFlow<String?>(null)
    val note = _note.asStateFlow()

    // The last turn's outcome, read by HandsFreeLoop once the turn is done: did the brain's
    // shared policy say the conversation is over (a stop phrase), or did the turn fail? Either
    // way the loop stops chaining follow-ups. Set true up-front and cleared only on a clean,
    // continuing turn, so a crash mid-turn defaults to "stop" rather than a runaway loop.
    @Volatile
    private var _endConversation = false
    val lastTurnEndedConversation: Boolean get() = _endConversation

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
            // Barge-in-lite: a tap while Crumpet is speaking shuts him up (the reply
            // text is already in the chat thread). Next tap records as normal.
            VoiceState.Speaking -> interrupt()
            else -> {}  // Thinking — nothing sensible to toggle
        }
    }

    /** Stop whatever is being spoken RIGHT NOW and don't speak the rest — the mic-tap
     *  barge-in and the "user swiped the app away" path (PresenceService.onTaskRemoved:
     *  the service process outlives the UI, so without this a long reply keeps talking
     *  to an empty room — field report 2026-07-15). Cancels the in-flight turn coroutine
     *  too, so a stream that hasn't produced audio yet can't start after the swipe; the
     *  brain finishes its turn regardless and the text lands via the exchange frame.
     *  Safe from any thread/state. */
    fun interrupt() {
        turnJob?.cancel()
        turnJob = null
        if (_state.value == VoiceState.Recording) recorder.stop()  // drop the capture — mic off
        TtsPlayer.stop()
        PcmStreamPlayer.stop()
        _state.value = VoiceState.Idle
    }

    private fun send(app: Context) {
        turnJob = scope.launch {
            val wav = withContext(Dispatchers.IO) { recorder.stop() }
            if (wav == null) {
                _state.value = VoiceState.Idle  // fumbled tap — too short to be speech
                return@launch
            }
            turn(app, wav)
        }
    }

    /** Run a turn from an already-captured WAV — the hands-free loop's entry (it does its
     *  own wake + capture, then hands the utterance here). No-op if a turn is already
     *  running, so an overlapping wake can't double-fire. Must be called with a WAV that
     *  actually holds speech; the loop's endpointing guarantees that. */
    fun sendWav(context: Context, wav: ByteArray) {
        if (_state.value != VoiceState.Idle) return
        _endConversation = true                 // pessimistic until a turn completes cleanly
        _state.value = VoiceState.Thinking      // set here (sync) so a waiting loop sees non-Idle at once
        turnJob = scope.launch { turn(context.applicationContext, wav) }
    }

    private suspend fun turn(app: Context, wav: ByteArray) {
        _state.value = VoiceState.Thinking
        val (base, tok, _) = Prefs(app).config()
        // Client-side timing (pairs with the brain's stage log) — the gap the field report
        // flagged. `adb logcat -s VoiceTiming` shows the whole chain across both halves.
        val t0 = android.os.SystemClock.elapsedRealtime()
        val result = runCatching {
            withContext(Dispatchers.IO) { Net.voice(base, tok, wav, stream = true) }
        }.getOrElse { e ->
            // A deliberate interrupt() — die quietly, never surface a note for it.
            // (runCatching swallows CancellationException; rethrowing keeps the
            // coroutine's cancellation semantics honest.)
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A timeout isn't a failure: the brain finishes the turn anyway and the reply
            // lands in chat via its exchange frame — only the spoken audio is lost. Common
            // while a game holds the GPU (cold partial-CPU loads, turns queueing).
            _note.value = if (e is HttpRequestTimeoutException)
                "Taking me a while — the answer will land in chat when it's ready."
            else
                "Voice failed: ${e.message?.take(80) ?: "can't reach the brain"}"
            _state.value = VoiceState.Idle
            return
        }
        val roundTrip = android.os.SystemClock.elapsedRealtime() - t0
        if (!result.ok) {
            _note.value = "Didn't catch that — try again?"
            _state.value = VoiceState.Idle
            return  // _endConversation stays true — don't chain a follow-up after a miss
        }
        _endConversation = result.endConversation  // brain's shared policy decides
        if (result.stream && result.ttsId != null) {
            streamedReply(app, base, tok, result.ttsId, t0)
            return
        }
        val tts = result.ttsId?.let { id ->
            withContext(Dispatchers.IO) {
                runCatching { Net.fetchTts(base, tok, id) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
            }
        }
        val ttsFetch = android.os.SystemClock.elapsedRealtime() - t0 - roundTrip
        android.util.Log.d(
            "VoiceTiming",
            "send->reply(+synth on brain)=${roundTrip}ms, tts fetch=${ttsFetch}ms",
        )
        if (tts == null) {
            _state.value = VoiceState.Idle  // reply text still lands via the exchange frame
            return
        }
        _state.value = VoiceState.Speaking
        TtsPlayer.play(app, tts) { _state.value = VoiceState.Idle }
        // Watchdog: MediaPlayer's completion callback occasionally doesn't fire (a hung or
        // odd audio left the face stuck on 'speaking'). The WAV's own length is a hard upper
        // bound, so force the turn closed a beat past it if playback never reported done.
        val guardMs = wavDurationMs(tts) + 1500
        scope.launch {
            delay(guardMs)
            if (_state.value == VoiceState.Speaking) {
                android.util.Log.w("VoiceSession", "TTS never reported done in ${guardMs}ms — forcing idle")
                TtsPlayer.stop()
                _state.value = VoiceState.Idle
            }
        }
    }

    /** A sentence-streamed reply (brain answered `stream: true`): the turn is still
     *  GENERATING on the brain — GET /tts/<id> feeds PCM chunks to the track as sentences
     *  synthesize, so speech starts seconds after STT instead of after the whole reply.
     *  Stays Thinking until the first audible chunk, then Speaking until the stream ends
     *  AND the track drains. Reply text lands via the exchange frame, same as ever.
     *  On stream failure the reply text still lands — same posture as a lost WAV fetch. */
    private suspend fun streamedReply(app: Context, base: String, tok: String, id: String, t0: Long) {
        var firstAudioMs = -1L
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                Net.streamTts(base, tok, id,
                    onRate = { rate -> PcmStreamPlayer.begin(app, rate) },
                    onChunk = { buf, len ->
                        if (firstAudioMs < 0) {
                            firstAudioMs = android.os.SystemClock.elapsedRealtime() - t0
                            _state.value = VoiceState.Speaking
                        }
                        PcmStreamPlayer.write(buf, len)
                    })
            }
        }
        outcome.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e  // interrupt() — die quietly
            android.util.Log.w("VoiceSession", "TTS stream failed: ${e.message}")
            _note.value = if (firstAudioMs < 0)
                "Taking me a while — the answer will land in chat when it's ready."
            else null  // it spoke partially; the chat thread has the full text
        }
        PcmStreamPlayer.drain()  // bounded by the audio's own length — can't wedge the turn
        android.util.Log.d(
            "VoiceTiming",
            "streamed: first audio=${if (firstAudioMs < 0) "none" else "${firstAudioMs}ms"}, " +
                "turn done=${android.os.SystemClock.elapsedRealtime() - t0}ms",
        )
        _state.value = VoiceState.Idle
    }

    /** Duration of a 16-bit mono WAV from its header (sample rate at offset 24). A rough
     *  upper bound is all the watchdog needs; falls back to a few seconds if malformed. */
    private fun wavDurationMs(wav: ByteArray): Long {
        if (wav.size < 44) return 4000
        val sr = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or
            ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24)
        if (sr <= 0) return 4000
        return (wav.size - 44).toLong() * 1000L / (sr.toLong() * 2L)
    }
}

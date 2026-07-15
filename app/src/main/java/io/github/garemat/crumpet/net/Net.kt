package io.github.garemat.crumpet.net

import io.github.garemat.crumpet.data.AttachResult
import io.github.garemat.crumpet.data.CalCreateBody
import io.github.garemat.crumpet.data.CalendarListResponse
import io.github.garemat.crumpet.data.HealthRecord
import io.github.garemat.crumpet.data.InFrame
import io.github.garemat.crumpet.data.IngestBatch
import io.github.garemat.crumpet.data.IngestResult
import io.github.garemat.crumpet.data.OutMessage
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.data.VoiceResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** Single place all brain I/O goes through: the ingest POST + the live chat/push WS. */
object Net {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                // Client-side WS pings: without these a dead tunnel is a ZOMBIE — the socket
                // looks connected forever, nothing arrives, and only an app restart recovers
                // (seen 2026-07-03: a reply finished mid-build and never landed). OkHttp kills
                // the connection when a ping goes unanswered → onClose → the reconnect loop
                // takes over and the history sync delivers whatever we missed.
                config { pingInterval(java.time.Duration.ofSeconds(20)) }
            }
            install(ContentNegotiation) { json(json) }
            install(WebSockets)
            // Per-request timeouts are set where needed; this just enables the plugin.
            install(HttpTimeout)
        }
    }

    // ---- health ingest ----
    suspend fun ingest(base: String, token: String, records: List<HealthRecord>): IngestResult {
        if (base.isBlank() || records.isEmpty()) return IngestResult(true)
        return client.post("$base/health/ingest") {
            header("X-Crumpet-Token", token)
            contentType(ContentType.Application.Json)
            setBody(IngestBatch(records))
        }.body()
    }

    // ---- calendar (brain-mediated CalDAV; crumpet docs/backlog/calendar.md phase 2) ----
    // The write calls speak the brain's outbox contract: 200 → done, 400 → the brain
    // PERMANENTLY rejected it (return the reason; the caller drops the mutation), anything
    // else (offline, 502 Radicale down, 503 unconfigured) → throw, the sync retries later.

    suspend fun calendarList(base: String, token: String, days: Int): CalendarListResponse {
        val resp = client.get("$base/calendar?days=$days") { header("X-Crumpet-Token", token) }
        if (resp.status.value != 200) throw IllegalStateException("calendar read ${resp.status.value}")
        return resp.body()
    }

    /** Returns null on success, the brain's reason when permanently rejected, throws when retryable. */
    suspend fun calendarCreate(base: String, token: String, body: CalCreateBody): String? {
        val resp = client.post("$base/calendar/event") {
            header("X-Crumpet-Token", token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return when (resp.status.value) {
            200 -> null
            400 -> resp.bodyAsText().take(200).ifBlank { "the brain refused it" }
            else -> throw IllegalStateException("calendar write ${resp.status.value}")
        }
    }

    /** Same contract as [calendarCreate]; deleting an already-gone uid is a 200 on the brain. */
    suspend fun calendarDelete(base: String, token: String, uid: String): String? {
        val resp = client.delete("$base/calendar/event/$uid") { header("X-Crumpet-Token", token) }
        return when (resp.status.value) {
            200 -> null
            400 -> resp.bodyAsText().take(200).ifBlank { "the brain refused it" }
            else -> throw IllegalStateException("calendar delete ${resp.status.value}")
        }
    }

    // ---- attachment (photo / PDF / doc) → /attach ----
    suspend fun attach(
        base: String, token: String, text: String,
        fileName: String, mime: String, bytes: ByteArray,
    ): AttachResult {
        if (base.isBlank()) return AttachResult(false, "Not paired.")
        return client.post("$base/attach") {
            // Vision can swap the model in + infer — give it room (default read timeout is ~10s).
            // Game mode stretches this further: every model load is cold + partial-CPU, and a
            // turn can queue behind another (e.g. a PDF ingest), so 3 minutes wasn't enough.
            timeout {
                requestTimeoutMillis = 300_000
                socketTimeoutMillis = 300_000
                connectTimeoutMillis = 30_000
            }
            header("X-Crumpet-Token", token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        if (text.isNotBlank()) append("text", text)
                        append(
                            "file", bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, mime.ifBlank { "application/octet-stream" })
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            },
                        )
                    },
                ),
            )
        }.body()
    }

    // ---- voice turn (PTT WAV) → /voice; reply audio ← /tts/<id> ----
    /** [stream]=true asks the brain for the sentence-streamed contract (?stream=pcm):
     *  the response returns right after STT and the tts id plays live via streamTts.
     *  An old brain ignores the query and answers the blocking WAV shape — VoiceResult
     *  tells them apart, so the caller degrades transparently. */
    suspend fun voice(base: String, token: String, wav: ByteArray, stream: Boolean = false): VoiceResult {
        if (base.isBlank()) return VoiceResult(false, error = "Not paired.")
        val url = if (stream) "$base/voice?stream=pcm" else "$base/voice"
        return client.post(url) {
            // STT + a full brain turn + TTS — same generous window as /attach.
            timeout {
                requestTimeoutMillis = 300_000
                socketTimeoutMillis = 300_000
                connectTimeoutMillis = 30_000
            }
            header("X-Crumpet-Token", token)
            contentType(ContentType("audio", "wav"))
            setBody(wav)
        }.body()
    }

    /** The synthesized reply is ephemeral on the brain (5-min TTL) — fetch it promptly. */
    suspend fun fetchTts(base: String, token: String, id: String): ByteArray =
        client.get("$base/tts/$id") {
            timeout {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
            }
            header("X-Crumpet-Token", token)
        }.body()

    /** Play a sentence-streamed reply as it synthesizes: GET /tts/<id> on a ?stream=pcm
     *  turn is chunked raw PCM16 mono whose bytes arrive WHILE the brain generates.
     *  [onRate] fires once with the sample rate (header, sent with the first sentence);
     *  [onChunk] gets each buffer as it lands. Returns when the brain finishes the reply.
     *  The response headers can take as long as the turn's first sentence (minutes if a
     *  game holds the GPU and the brain degrades to whole-reply synthesis), so the
     *  request window matches /voice; the socket window only bounds mid-reply stalls. */
    suspend fun streamTts(
        base: String,
        token: String,
        id: String,
        onRate: suspend (Int) -> Unit,
        onChunk: suspend (ByteArray, Int) -> Unit,
    ) {
        client.prepareGet("$base/tts/$id") {
            timeout {
                requestTimeoutMillis = 300_000
                socketTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
            }
            header("X-Crumpet-Token", token)
        }.execute { resp ->
            onRate(resp.headers["X-Sample-Rate"]?.toIntOrNull() ?: 24_000)
            val channel = resp.bodyAsChannel()
            val buf = ByteArray(16_384)
            // 16-bit alignment: network reads land on arbitrary byte boundaries, and
            // handing an ODD length to AudioTrack splits a sample — every sample after
            // it is byte-shifted garbage (LOUD static) until another odd read
            // rebalances. Carry the odd tail byte into the next chunk instead.
            var carry = -1
            while (true) {
                var off = 0
                if (carry >= 0) {
                    buf[0] = carry.toByte()
                    off = 1
                    carry = -1
                }
                val n = channel.readAvailable(buf, off, buf.size - off)
                if (n == -1) {
                    break  // a dangling half-sample at EOF is dropped by design
                }
                var len = off + n
                if (len % 2 != 0) {
                    // `and 0xFF`: Byte.toInt() sign-extends, and a carried byte >= 0x80
                    // would read as negative — colliding with the -1 "no carry" sentinel
                    // and getting DROPPED, which flips the alignment permanently (the
                    // "static got worse" report, 2026-07-16). Keep carry in 0..255.
                    carry = buf[len - 1].toInt() and 0xFF
                    len -= 1
                }
                if (len > 0) onChunk(buf, len)
            }
        }
    }

    // ---- files Crumpet offers (a {"type":"file"} frame carries the id; this gets the bytes) ----
    suspend fun fetchFile(base: String, token: String, id: String): ByteArray =
        client.get("$base/file/$id") {
            timeout {
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
            }
            header("X-Crumpet-Token", token)
        }.body()

    // ---- live chat / push connection (held open by PresenceService) ----
    private val _frames = MutableSharedFlow<InFrame>(extraBufferCapacity = 64)
    val frames = _frames.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status     // human-readable, surfaced in Setup for feedback

    // User messages ride the PERSISTED outbox (Prefs) so they survive restarts and offline gaps;
    // the WS session drains it in order and each delivered id is emitted on [sent] so the UI can
    // clear its pending mark. Control frames (ack/read) stay volatile — the brain re-flushes
    // undelivered pushes on reconnect anyway, so losing one costs nothing.
    private val control = Channel<String>(Channel.BUFFERED)
    private val queued = Channel<Unit>(Channel.CONFLATED)    // "outbox has new content" signal
    private val retryKick = Channel<Unit>(Channel.CONFLATED) // "network is back — retry now" signal
    private val _sent = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    val sent = _sent.asSharedFlow()

    /** Signal that the caller just persisted a new outbox entry (persist FIRST, then call this). */
    fun notifyQueued() { queued.trySend(Unit) }

    /** Collapse the reconnect backoff — called when Android reports connectivity returned. */
    fun retryNow() { retryKick.trySend(Unit) }

    /** Tell the brain this device received a proactive push (stop redelivering it).
     *  Non-blocking: if offline the brain re-flushes pending on reconnect anyway. */
    fun ack(id: Int) { control.trySend("""{"type":"ack","id":$id}""") }

    /** Tell the brain the user has seen a proactive push (dismiss it on other devices). */
    fun read(id: Int) { control.trySend("""{"type":"read","id":$id}""") }

    /** Confirm (or refuse) a device action the brain fired — it's waiting on this to tell
     *  the user "on it" vs "your phone didn't pick that up". */
    fun ackAction(id: String, ok: Boolean) {
        control.trySend("""{"type":"action_ack","id":"$id","ok":$ok}""")
    }

    // Full-screen/car mode parks the brain's workshop while it holds (car-mode.md). The flag
    // is remembered so a RECONNECT re-asserts it — the brain scopes it to the socket's life,
    // so a drop while the face is up would otherwise silently un-park the workshop.
    @Volatile private var engagedNow = false

    fun engaged(active: Boolean) {
        engagedNow = active
        control.trySend("""{"type":"engaged","active":$active}""")
    }

    private suspend fun DefaultClientWebSocketSession.drainOutbox(prefs: Prefs) {
        for (m in prefs.outbox()) {
            send(Frame.Text(json.encodeToString(OutMessage(text = m.text))))
            prefs.removeFromOutbox(m.id)  // only after the frame is written to the open socket
            _sent.emit(m.id)
        }
    }

    /** Maintain the WS with reconnect-on-drop until the calling scope is cancelled.
     *  Backoff doubles 4s → 5min while unreachable (an off-VPN phone shouldn't burn battery
     *  hammering every 4s) and resets on success; retryNow() short-circuits the wait. */
    suspend fun maintain(base: String, token: String, prefs: Prefs) {
        if (base.isBlank() || token.isBlank()) {
            _status.value = "Enter the brain URL + token first"
            return
        }
        val wsUrl = base.replaceFirst("http", "ws") + "/chat?token=" + token
        var backoff = 4_000L
        while (coroutineContext.isActive) {
            _status.value = "Connecting…"
            try {
                client.webSocket(wsUrl) {
                    _connected.value = true
                    _status.value = "Connected over WireGuard"
                    backoff = 4_000L
                    val sender = launch {
                        if (engagedNow) send(Frame.Text("""{"type":"engaged","active":true}"""))
                        drainOutbox(prefs)  // deliver anything queued while offline, in order
                        while (isActive) {
                            select<Unit> {
                                control.onReceive { send(Frame.Text(it)) }
                                queued.onReceive { drainOutbox(prefs) }
                            }
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                runCatching { json.decodeFromString<InFrame>(frame.readText()) }
                                    .getOrNull()?.let { _frames.emit(it) }
                            }
                        }
                    } finally {
                        sender.cancel()
                    }
                }
            } catch (e: Exception) {
                _status.value = "Can't reach Crumpet — " + (e.message?.take(60) ?: "check the URL/VPN")
            }
            _connected.value = false
            if (coroutineContext.isActive) {
                withTimeoutOrNull(backoff) { retryKick.receive() }
                backoff = min(backoff * 2, 300_000L)
            }
        }
    }
}

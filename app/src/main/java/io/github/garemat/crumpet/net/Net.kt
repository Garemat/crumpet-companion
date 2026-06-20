package io.github.garemat.crumpet.net

import io.github.garemat.crumpet.data.HealthRecord
import io.github.garemat.crumpet.data.InFrame
import io.github.garemat.crumpet.data.IngestBatch
import io.github.garemat.crumpet.data.IngestResult
import io.github.garemat.crumpet.data.OutMessage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

/** Single place all brain I/O goes through: the ingest POST + the live chat/push WS. */
object Net {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(WebSockets)
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

    // ---- live chat / push connection (held open by PresenceService) ----
    private val _frames = MutableSharedFlow<InFrame>(extraBufferCapacity = 64)
    val frames = _frames.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val outbox = Channel<String>(Channel.BUFFERED)

    suspend fun send(text: String) {
        if (text.isNotBlank()) outbox.send(json.encodeToString(OutMessage(text = text)))
    }

    /** Maintain the WS with reconnect-on-drop until the calling scope is cancelled. */
    suspend fun maintain(base: String, token: String) {
        if (base.isBlank() || token.isBlank()) return
        val wsUrl = base.replaceFirst("http", "ws") + "/chat?token=" + token
        while (coroutineContext.isActive) {
            try {
                client.webSocket(wsUrl) {
                    _connected.value = true
                    val sender = launch { for (m in outbox) send(Frame.Text(m)) }
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
            } catch (_: Exception) {
                // network blip / VPN down — fall through to backoff + retry
            }
            _connected.value = false
            delay(4000)
        }
    }
}

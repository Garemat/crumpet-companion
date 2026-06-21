package io.github.garemat.crumpet.net

import io.github.garemat.crumpet.data.AttachResult
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
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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

    // ---- attachment (photo / PDF / doc) → /attach ----
    suspend fun attach(
        base: String, token: String, text: String,
        fileName: String, mime: String, bytes: ByteArray,
    ): AttachResult {
        if (base.isBlank()) return AttachResult(false, "Not paired.")
        return client.post("$base/attach") {
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

    // ---- live chat / push connection (held open by PresenceService) ----
    private val _frames = MutableSharedFlow<InFrame>(extraBufferCapacity = 64)
    val frames = _frames.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status     // human-readable, surfaced in Setup for feedback
    private val outbox = Channel<String>(Channel.BUFFERED)

    suspend fun send(text: String) {
        if (text.isNotBlank()) outbox.send(json.encodeToString(OutMessage(text = text)))
    }

    /** Maintain the WS with reconnect-on-drop until the calling scope is cancelled. */
    suspend fun maintain(base: String, token: String) {
        if (base.isBlank() || token.isBlank()) {
            _status.value = "Enter the brain URL + token first"
            return
        }
        val wsUrl = base.replaceFirst("http", "ws") + "/chat?token=" + token
        while (coroutineContext.isActive) {
            _status.value = "Connecting…"
            try {
                client.webSocket(wsUrl) {
                    _connected.value = true
                    _status.value = "Connected over WireGuard"
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
            } catch (e: Exception) {
                _status.value = "Can't reach Crumpet — " + (e.message?.take(60) ?: "check the URL/VPN")
            }
            _connected.value = false
            if (coroutineContext.isActive) delay(4000)
        }
    }
}

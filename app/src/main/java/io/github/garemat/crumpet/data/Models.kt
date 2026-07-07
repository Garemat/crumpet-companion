package io.github.garemat.crumpet.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One record shipped to the brain's POST /health/ingest. Shape matches core/health_ingest.py. */
@Serializable
data class HealthRecord(
    val type: String,                 // nutrition | weight | bodyfat | steps | sleep | workout
    val day: String? = null,          // "YYYY-MM-DD" for daily aggregates
    val calories: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    val value: Double? = null,        // weight / bodyfat / steps
    val unit: String? = null,
    val minutes: Double? = null,      // sleep
    val uid: String? = null,          // workout dedupe key (Health Connect record id)
    val title: String? = null,
    val detail: String? = null,
    val at: String? = null,           // workout local time "YYYY-MM-DD HH:MM"
)

@Serializable
data class IngestBatch(val records: List<HealthRecord>)

@Serializable
data class IngestResult(val ok: Boolean = false, val counts: Map<String, Int> = emptyMap())

@Serializable
data class AttachResult(val ok: Boolean = false, val reply: String = "")

/** POST /voice response. [ok]=false + [error] covers the brain's junk-transcript guard
 *  ("nothing heard"); [ttsId] is null when synthesis failed (text reply still stands). */
@Serializable
data class VoiceResult(
    val ok: Boolean = false,
    val heard: String = "",
    val reply: String? = null,
    @SerialName("tts_id") val ttsId: String? = null,
    val error: String? = null,
)

// ---- chat gateway frames (mirror crumpet/gateway/chat_ws.py) ----
@Serializable
data class OutMessage(val type: String = "message", val text: String)

@Serializable
data class InFrame(
    val type: String,
    val text: String? = null,
    val value: String? = null,
    // "id" is OVERLOADED on the wire: an Int for push/dismiss frames, a String handle for
    // file frames — so it's held as a raw primitive and read through pushId/fileId below.
    val id: JsonPrimitive? = null,
    val messages: List<HistMsg>? = null,
    val phase: String? = null,                 // activity milestone phase (plan/build/review/pr/…)
    val source: String? = null,                // exchange: originating channel (discord/voice:…/app)
    val user: String? = null,                  // exchange: the user side of the completed turn
    val reply: String? = null,                 // exchange: Crumpet's side of the completed turn
    val name: String? = null,                  // file: original filename
    val caption: String? = null,               // file: Crumpet's caption
    val mime: String? = null,                  // file: content type
    val size: Long? = null,                    // file: bytes
    val verb: String? = null,                  // action: navigate | media (ActionRunner's table)
    val query: String? = null,                 // action navigate: destination (plain text, never a URI)
    val control: String? = null,               // action media: play | pause | next | prev
) {
    val pushId: Int? get() = id?.intOrNull                              // push / dismiss frames
    val fileId: String? get() = id?.takeIf { it.isString }?.content     // file frames
    val actionId: String? get() = fileId                                // action frames (same shape)
}

@Serializable
data class HistMsg(val role: String, val text: String, val source: String? = null, val ts: String? = null)

/** A chat line in the UI. [imageUri] = a LOCAL file path for an image the user sent (never synced
 *  from the brain); shown as a thumbnail for human context. Serializable because the last ~100
 *  lines are cached locally so the chat isn't blank when the brain/VPN is unreachable.
 *  [pending] = queued in the outbox, not yet delivered; [id] links the line to its outbox entry. */
@Serializable
data class ChatLine(
    val fromCrumpet: Boolean,
    val text: String,
    val source: String? = null,
    val imageUri: String? = null,
    val id: Long = 0L,
    val pending: Boolean = false,
    val fileId: String? = null,   // set on lines carrying a file Crumpet sent (chart/photo/…)
    val fileName: String? = null,
    val mime: String? = null,
)

/** A user message queued for delivery (persisted outbox — survives restarts and offline gaps). */
@Serializable
data class QueuedMsg(val id: Long, val text: String, val ts: Long)

/** A file Crumpet sent us (chart, progress photos, app preview…), fetched from the brain's
 *  GET /file/<id> and stored locally — app-local like [SentImage], since the brain's history
 *  sync doesn't carry files. [ts] (received-at, epoch millis) orders it into rebuilt history. */
@Serializable
data class InboxFile(
    val id: String,
    val name: String,
    val caption: String,
    val mime: String,
    val path: String,
    val ts: Long,
) {
    fun toChatLine() = ChatLine(
        fromCrumpet = true,
        text = caption,
        imageUri = path.takeIf { mime.startsWith("image/") },
        fileId = id,
        fileName = name,
        mime = mime,
    )
}

/** Record of an image the user sent, kept locally so its thumbnail survives history reloads.
 *  [marker] is exactly the user-text the brain echoes back in history (so we can re-attach). */
@Serializable
data class SentImage(val marker: String, val path: String, val ts: Long)

/** Today's health glance for Home/Health screens. */
data class HealthSnapshot(
    val calories: Int? = null,
    val protein: Int? = null,
    val carbs: Int? = null,
    val fat: Int? = null,
    val weightKg: Double? = null,
    val steps: Int? = null,
    val sleepMinutes: Int? = null,
    val recentWorkouts: List<WorkoutLine> = emptyList(),
)

data class WorkoutLine(val title: String, val detail: String, val source: String)

/** A calendar event for the agenda strip / "Up next". */
data class AgendaItem(val title: String, val startMillis: Long, val allDay: Boolean)

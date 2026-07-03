package io.github.garemat.crumpet.data

import kotlinx.serialization.Serializable

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

// ---- chat gateway frames (mirror crumpet/gateway/chat_ws.py) ----
@Serializable
data class OutMessage(val type: String = "message", val text: String)

@Serializable
data class InFrame(
    val type: String,
    val text: String? = null,
    val value: String? = null,
    val id: Int? = null,                       // proactive push / dismiss id
    val messages: List<HistMsg>? = null,
    val phase: String? = null,                 // activity milestone phase (plan/build/review/pr/…)
    val source: String? = null,                // exchange: originating channel (discord/voice:…/app)
    val user: String? = null,                  // exchange: the user side of the completed turn
    val reply: String? = null,                 // exchange: Crumpet's side of the completed turn
)

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
)

/** A user message queued for delivery (persisted outbox — survives restarts and offline gaps). */
@Serializable
data class QueuedMsg(val id: Long, val text: String, val ts: Long)

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

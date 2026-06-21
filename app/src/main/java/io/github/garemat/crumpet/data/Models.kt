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
    val messages: List<HistMsg>? = null,
)

@Serializable
data class HistMsg(val role: String, val text: String, val source: String? = null, val ts: String? = null)

/** A chat line in the UI. */
data class ChatLine(val fromCrumpet: Boolean, val text: String, val source: String? = null)

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

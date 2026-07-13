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
    // The brain's shared conversation policy said the user closed the chat ("no follow up").
    // The hands-free loop stands down instead of waiting out the follow-up window.
    @SerialName("end_conversation") val endConversation: Boolean = false,
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
    val verb: String? = null,                  // action: navigate | media | wake_spotify (ActionRunner's table)
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

// ---- calendar (brain-mediated: GET/POST/DELETE /calendar…; crumpet docs/backlog/calendar.md) ----

/** One server-expanded event instance from GET /calendar, cached locally so the Calendar
 *  screen works offline. Times are the brain's local-naive ISO strings ("2026-07-16T18:00:00",
 *  or "2026-07-16" for all-day) — lexicographic order IS chronological order, all-day first.
 *  Recurring events appear as one row per occurrence sharing a [uid]; [pending] is local-only
 *  (a queued create not yet on the brain). */
@Serializable
data class CalEvent(
    val uid: String,
    val summary: String,
    val start: String? = null,
    val end: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val notes: String = "",
    val recurring: Boolean = false,
    @SerialName("recurrence_id") val recurrenceId: String? = null,
    val pending: Boolean = false,
)

/** The instance's calendar day — every start form begins "YYYY-MM-DD". */
fun CalEvent.day(): java.time.LocalDate? =
    runCatching { java.time.LocalDate.parse(start?.take(10)) }.getOrNull()

/** "HH:MM" for timed events (fixed ISO positions — tolerant of a trailing offset), "" all-day. */
fun CalEvent.timeLabel(): String =
    if (allDay || start == null || start.length < 16 || 'T' !in start) "" else start.substring(11, 16)

/** Epoch millis of the instance start in the system zone (brain times are home-local naive;
 *  a DAVx5-era event may carry an offset — both parse). Null when absent/unparseable. */
fun CalEvent.startMillis(): Long? {
    val s = start ?: return null
    val zone = java.time.ZoneId.systemDefault()
    return runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() }
        .recoverCatching {
            java.time.LocalDate.parse(s.take(10)).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        .getOrNull()
}

@Serializable
data class CalendarListResponse(val ok: Boolean = false, val days: Int = 0,
                                val events: List<CalEvent> = emptyList())

/** A queued calendar mutation (persisted outbox, same model as the chat outbox).
 *  [kind] = "create" | "delete". The app generates [uid] — the brain saves PUT-by-uid,
 *  so a replayed create overwrites instead of duplicating. [start] uses the brain's
 *  tool format: "YYYY-MM-DD HH:MM", or "YYYY-MM-DD" for all-day. */
@Serializable
data class CalMutation(
    val id: Long,
    val kind: String,
    val uid: String,
    val summary: String = "",
    val start: String = "",
    val end: String = "",
    val allDay: Boolean = false,
    val notes: String = "",
    val repeat: String = "",
    val repeatDays: List<String> = emptyList(),
    val repeatUntil: String = "",
) {
    /** The optimistic row shown (pending) until the brain confirms it into the cache. */
    fun toCalEvent() = CalEvent(
        uid = uid, summary = summary,
        start = if (allDay || " " !in start) start else start.replace(" ", "T") + ":00",
        allDay = allDay, notes = notes, recurring = repeat.isNotBlank() || repeatDays.isNotEmpty(),
        pending = true,
    )
}

/** POST /calendar/event body — field names are the brain's endpoint contract. */
@Serializable
data class CalCreateBody(
    val uid: String,
    val summary: String,
    val start: String,
    val end: String = "",
    @SerialName("all_day") val allDay: Boolean = false,
    val notes: String = "",
    val repeat: String = "",
    @SerialName("repeat_days") val repeatDays: List<String> = emptyList(),
    @SerialName("repeat_until") val repeatUntil: String = "",
)

package io.github.garemat.crumpet.gym

import kotlinx.serialization.json.Json

/** Pure helpers behind the History screens (unit-tested). */
object HistoryOps {
    private val json = Json { ignoreUnknownKeys = true }

    /** Finished sessions still in the outbox, newest first, shaped like history rows —
     *  so a workout shows up the moment you finish it, signal or not. */
    fun pending(outbox: List<GymOutboxItem>): List<HistorySession> = outbox
        .filter { it.method == "POST" && it.path == "/gym/sessions" }
        .mapNotNull { runCatching { json.decodeFromString<SessionBody>(it.body) }.getOrNull() }
        .map { b ->
            HistorySession(
                uid = b.uid, title = b.title, source = "app", at = b.startedAt,
                durationMin = minutesBetween(b.startedAt, b.endedAt), note = b.note, pending = true,
                exercises = b.exercises.map { e ->
                    HistoryExercise(e.exercise, e.superset, e.note,
                        e.sets.map { HistorySet(it.type, it.weight, it.reps, it.dropOf) })
                },
            )
        }
        .sortedByDescending { it.at }

    /** Queued first, then the brain's pages; a queued session the brain already has (its
     *  uid came back in a page — the POST landed, the outbox just hasn't been pruned in
     *  this view yet) shows once. */
    fun merge(pending: List<HistorySession>, pages: List<HistorySession>): List<HistorySession> {
        val landed = pages.mapNotNull { it.uid }.toSet()
        val seen = HashSet<String>()
        return (pending.filter { it.uid !in landed } + pages).filter { seen.add(it.key) }
    }

    /** One lift's sessions, its sets only — the per-exercise view from cached pages when
     *  the brain can't be asked. */
    fun forExercise(sessions: List<HistorySession>, name: String): List<HistorySession> =
        sessions.mapNotNull { s ->
            val ex = s.exercises.filter { it.exercise == name }
            if (ex.isEmpty()) null else s.copy(exercises = ex)
        }

    fun setCount(s: HistorySession) = s.exercises.sumOf { it.sets.size }
    fun prCount(s: HistorySession) = s.exercises.sumOf { e -> e.sets.count { it.prs.isNotEmpty() } }

    private fun minutesBetween(a: String, b: String): Int? = runCatching {
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        java.time.Duration.between(java.time.LocalDateTime.parse(a, f), java.time.LocalDateTime.parse(b, f))
            .toMinutes().toInt().coerceAtLeast(0)
    }.getOrNull()
}

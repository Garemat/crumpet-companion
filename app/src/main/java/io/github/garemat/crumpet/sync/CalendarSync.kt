package io.github.garemat.crumpet.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.garemat.crumpet.data.CalCreateBody
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.net.Net
import java.util.concurrent.TimeUnit

/** Calendar sync: drain the queued mutations to the brain IN ORDER, then refresh the cached
 *  window. Radicale (via the brain) stays the single source of truth — the phone is a caching
 *  client. "VPN comes back" needs no special detection: an unreachable brain just means retry
 *  (WorkManager backoff), and the reconnect hook in PresenceService kicks us early. */
object CalendarSync {
    const val WINDOW_DAYS = 60  // the brain expands RRULEs server-side; we just cache the window

    /** True when everything queued was delivered AND the window refreshed (worker → success). */
    suspend fun syncNow(context: Context): Boolean {
        val prefs = Prefs(context)
        val (base, token, _) = prefs.config()
        if (base.isBlank() || token.isBlank()) return true // not paired — nothing to retry

        // Outbox first, in order, so our own writes are in the window we then pull. A permanent
        // rejection (400) drops the mutation but leaves a note for the Calendar screen — the
        // optimistic row vanishing silently would look like data loss. Retryable errors stop
        // the drain (order preserved) and the worker backs off.
        for (m in prefs.calOutbox()) {
            val rejection = try {
                when (m.kind) {
                    "create" -> Net.calendarCreate(
                        base, token,
                        CalCreateBody(
                            uid = m.uid, summary = m.summary, start = m.start, end = m.end,
                            allDay = m.allDay, notes = m.notes, repeat = m.repeat,
                            repeatDays = m.repeatDays, repeatUntil = m.repeatUntil,
                        ),
                    )
                    "delete" -> Net.calendarDelete(base, token, m.uid)
                    else -> null // unknown kind — drop it rather than wedge the queue forever
                }
            } catch (_: Exception) {
                return false
            }
            prefs.removeFromCalOutbox(m.id)
            if (rejection != null && m.kind == "create") {
                prefs.setCalNote("Couldn't save '${m.summary}': $rejection")
            }
        }

        return try {
            prefs.setCalCache(Net.calendarList(base, token, WINDOW_DAYS).events)
            true
        } catch (_: Exception) {
            false
        }
    }
}

class CalendarSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (CalendarSync.syncNow(applicationContext)) Result.success() else Result.retry()

    companion object {
        private const val NAME = "crumpet-calendar-sync"

        /** One-shot sync — called after each local mutation, on app open, and on WS reconnect.
         *  APPEND_OR_REPLACE (not KEEP) so a mutation queued mid-sync isn't missed by a run
         *  that already read the outbox. */
        fun kick(context: Context) {
            val req = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

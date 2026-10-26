package io.github.garemat.crumpet.gym

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.net.Net
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** Gym sync — the calendar pattern: drain the outbox IN ORDER, and only when everything
 *  landed pull a fresh /gym/state (so our own writes are in the snapshot we then cache —
 *  no re-applying optimistic edits over a pull). A 400 is the brain refusing permanently:
 *  drop the item and leave a note. Anything else (offline, VPN down) stops the drain,
 *  keeps the cache, and WorkManager backs off. The gym has bad signal; this is the norm. */
object GymSync {
    private val json = Json { ignoreUnknownKeys = true }

    /** True when the outbox emptied AND state refreshed (worker → success). */
    suspend fun syncNow(context: Context): Boolean {
        val (base, token, _) = Prefs(context).config()
        if (base.isBlank() || token.isBlank()) return true  // not paired — nothing to retry
        val store = GymStore.get(context)

        for (item in store.outbox.value) {
            val result = try {
                Net.gymSend(base, token, item)
            } catch (_: Exception) {
                return false
            }
            store.removeFromOutbox(item.id)
            when (result) {
                is Net.GymWrite.Refused -> {
                    if (item.path == "/gym/sessions") store.keepRejected(item)
                    store.setMeta {
                        it.copy(note = "Couldn't save ${item.label.ifBlank { "a change" }}: ${result.reason}")
                    }
                }
                is Net.GymWrite.Ok -> if (item.path == "/gym/sessions") {
                    runCatching { json.decodeFromString<SessionResult>(result.body) }.getOrNull()
                        ?.takeIf { it.feedback.isNotBlank() && !it.replay }
                        ?.let { r -> store.setMeta { it.copy(note = r.feedback) } }
                }
            }
        }

        return try {
            store.saveState(Net.gymState(base, token))
            store.saveHistory(Net.gymHistory(base, token))
            Net.gymLibrary(base, token, store.meta.value.libraryEtag)?.let { (etag, lib) ->
                store.saveLibrary(lib, etag)
            }
            store.setMeta { it.copy(lastSyncMillis = System.currentTimeMillis()) }
            true
        } catch (_: Exception) {
            false
        }
    }
}

class GymSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (GymSync.syncNow(applicationContext)) Result.success() else Result.retry()

    companion object {
        private const val NAME = "crumpet-gym-sync"

        /** After each local write, on opening the gym tab, and on WS reconnect.
         *  APPEND_OR_REPLACE so a write queued mid-sync isn't missed by a run that already
         *  read the outbox. */
        fun kick(context: Context) {
            val req = OneTimeWorkRequestBuilder<GymSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

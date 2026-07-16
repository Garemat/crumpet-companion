package io.github.garemat.crumpet.geo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** The Play-free presence checker: every ~15 min (and on demand) take ONE location fix,
 *  classify it against the user's places (PresenceCheck), queue any transition events and
 *  drain them to the brain's POST /presence. Coordinates are used here and discarded —
 *  only labels (+ a geocoded town when leaving somewhere) ever leave the phone.
 *
 *  Deliberately not GeofencingClient: that lives in Play services, and this app stays
 *  local/Google-free. The cost is transition latency (up to one check interval), which the
 *  brain's consumers absorb (the gym check-in already waits ~20 min after "left"). */
class PresenceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = PlacesStore(applicationContext)
        val prefs = Prefs(applicationContext)
        val (base, token, _) = prefs.config()

        if (!store.isPaused()) {
            check(store)
        }
        // Drain whatever is queued (including a backlog from offline checks) even when
        // paused — pause stops new COLLECTION, not delivery of what already happened.
        val queued = store.outbox()
        if (queued.isNotEmpty() && base.isNotBlank() && token.isNotBlank()) {
            try {
                Net.presencePost(base, token, queued)
                store.removeFromOutbox(queued)
            } catch (_: Exception) {
                // Brain unreachable — the outbox holds; the next run (or the WS-reconnect
                // kick) retries. Never Result.retry(): a dead VPN would spin the backoff.
            }
        }
        return Result.success()
    }

    private suspend fun check(store: PlacesStore) {
        val places = store.places()
        if (places.isEmpty() || !hasLocationPermission(applicationContext)) return
        val fix = Locator.currentFix(applicationContext)
            ?: return  // no fix (indoors, location off) → keep last state

        val last = store.lastLabel()
        val at = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        // Geocode BEFORE classify only when it could be needed (a possible exit-to-away);
        // the classifier itself never sees a coordinate API.
        val area = if (last.isNotEmpty()) geocodeTown(fix) else null
        val outcome = PresenceCheck.classify(
            places, last, PresenceCheck.Fix(fix.latitude, fix.longitude, fix.accuracy.toDouble()),
            area, at,
        )
        if (outcome.label != last) store.setLastLabel(outcome.label)
        store.addToOutbox(outcome.events)
    }

    /** Town/city for an away label — on-device only, and entirely optional: a degoogled
     *  phone may have no geocoder backend (Geocoder.isPresent false) → bare "away". */
    private suspend fun geocodeTown(fix: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION") // sync variant: fine off the main thread, works on all APIs
            Geocoder(applicationContext).getFromLocation(fix.latitude, fix.longitude, 1)
                ?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull()
    }

    companion object {
        private const val NAME = "crumpet-presence-check"
        private const val ONCE = "crumpet-presence-once"

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun hasBackgroundPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /** 15 min is WorkManager's floor — and the right cadence anyway (one coarse fix
         *  per interval ≈ no battery story, transitions land within a check). */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<PresenceWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Immediate one-shot — app opened, places edited, or the WS reconnected (drains
         *  any offline backlog the moment the brain is reachable). */
        fun kick(context: Context) {
            val req = OneTimeWorkRequestBuilder<PresenceWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
        }
    }
}

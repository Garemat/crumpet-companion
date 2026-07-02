package io.github.garemat.crumpet.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.health.HealthRepo
import io.github.garemat.crumpet.net.Net
import java.util.concurrent.TimeUnit

/** Reads new Health Connect records since the watermark and ships them to the brain.
 *  Only advances the watermark on a successful POST, so a failure just retries the window. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val (base, token, watermark) = prefs.config()
        if (base.isBlank() || token.isBlank()) return Result.success() // not paired yet

        val repo = HealthRepo(applicationContext)
        if (!repo.available || !repo.hasAllPermissions()) return Result.success()

        return try {
            // Rolling window + idempotent brain → no watermark gymnastics, nothing missed.
            Net.ingest(base, token, repo.recentRecords(30))
            prefs.setWatermark(System.currentTimeMillis())
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "crumpet-health-sync"
        private const val ONCE = "crumpet-health-sync-once"
        private const val MIN_GAP_MS = 15 * 60 * 1000L  // reconnect-triggered syncs, throttled

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** One-shot sync on reconnect, so data lands right after downtime instead of waiting
         *  for the 3h timer. Throttled via the watermark so a flappy VPN doesn't spam syncs. */
        suspend fun syncOnce(context: Context) {
            val (_, _, watermark) = Prefs(context).config()
            if (System.currentTimeMillis() - watermark < MIN_GAP_MS) return
            val req = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
        }
    }
}

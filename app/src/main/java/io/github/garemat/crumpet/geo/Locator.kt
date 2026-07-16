package io.github.garemat.crumpet.geo

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One-shot platform location fix (no Play services — LocationManager only). Shared by the
 *  presence worker and the Places screen's "use current location". Null when no provider is
 *  enabled, permission is missing, or nothing fixes within the timeout. */
object Locator {

    suspend fun currentFix(context: Context, timeoutMs: Long = 60_000): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(
                        lm, provider, signal,
                        ContextCompat.getMainExecutor(context),
                    ) { location -> if (cont.isActive) cont.resume(location) }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}

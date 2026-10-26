package io.github.garemat.crumpet.gym

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.garemat.crumpet.MainActivity
import io.github.garemat.crumpet.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Runs for the length of a workout: an ongoing notification ("Push · 12 sets", one tap
 *  back into the session from the lock screen) that becomes the rest countdown when a set
 *  is ticked, with −15s / +15s / Skip. At zero it vibrates and sounds — with the screen
 *  off, which is why this is a foreground service holding a partial wake lock for the rest
 *  (never longer than the rest itself). It stops itself when the session ends. */
class GymSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var alarm: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
        ServiceCompat.startForeground(this, ONGOING_ID, build(null, null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        val store = GymStore.get(this)
        scope.launch {
            combine(store.active, RestTimer.rest) { s, r -> s to r }.collect { (s, r) ->
                if (s == null) { RestTimer.skip(); stopSelf(); return@collect }
                nm().notify(ONGOING_ID, build(s, r))
                schedule(r)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLUS -> RestTimer.add(15)
            ACTION_MINUS -> RestTimer.add(-15)
            ACTION_SKIP -> RestTimer.skip()
        }
        return START_NOT_STICKY  // the session file survives; the app restarts us on reopen
    }

    override fun onDestroy() {
        alarm?.cancel()
        releaseWake()
        scope.cancel()
        super.onDestroy()
    }

    /** One pending alarm for the current rest; replaced on every ±15s / new rest. */
    private fun schedule(r: RestTimer.Rest?) {
        alarm?.cancel()
        if (r == null) { releaseWake(); return }
        holdWake(r.endsAt - System.currentTimeMillis() + 10_000)
        alarm = scope.launch {
            delay((r.endsAt - System.currentTimeMillis()).coerceAtLeast(0))
            if (RestTimer.rest.value?.endsAt == r.endsAt) {
                alert(r.label)
                RestTimer.skip()
            }
        }
    }

    private fun alert(label: String) {
        val vib = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(VibratorManager::class.java)).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350, 150, 600), -1))
        nm().notify(DONE_ID, NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Rest's up")
            .setContentText(if (label.isBlank()) "Next set" else "Next: $label")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openSession(this))
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build())
    }

    private fun build(s: ActiveSession?, r: RestTimer.Rest?): Notification {
        val b = NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // it's a timer, not health data
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(openSession(this))
            .setContentTitle(s?.title ?: "Workout")
        if (r != null) {
            b.setContentText(if (r.label.isBlank()) "Resting" else "Resting · next ${r.label}")
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(r.endsAt)
                .setShowWhen(true)
                .addAction(0, "−15s", action(ACTION_MINUS))
                .addAction(0, "+15s", action(ACTION_PLUS))
                .addAction(0, "Skip", action(ACTION_SKIP))
        } else {
            val ticked = s?.exercises?.sumOf { e -> e.sets.count { it.done } } ?: 0
            b.setContentText("$ticked sets · in progress").setShowWhen(false)
        }
        return b.build()
    }

    private fun action(a: String) = PendingIntent.getService(this, a.hashCode(),
        Intent(this, GymSessionService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun holdWake(ms: Long) {
        val w = wake ?: (getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crumpet:rest").also {
                it.setReferenceCounted(false); wake = it
            }
        w.acquire(ms.coerceIn(1_000, 20 * 60_000))
    }

    private fun releaseWake() { wake?.takeIf { it.isHeld }?.release() }

    private fun nm() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CH_ONGOING = "gym-session"
        private const val CH_DONE = "gym-rest-done"
        private const val ONGOING_ID = 4101
        private const val DONE_ID = 4102
        private const val ACTION_PLUS = "gym.rest.plus"
        private const val ACTION_MINUS = "gym.rest.minus"
        private const val ACTION_SKIP = "gym.rest.skip"

        /** Called when a session starts, and on app open if one is running. Idempotent. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GymSessionService::class.java))
        }

        private fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Workout in progress",
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Rest timer",
                NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }

        fun openSession(context: Context): PendingIntent = PendingIntent.getActivity(context, 4100,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, "gym/session")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

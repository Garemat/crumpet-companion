package io.github.garemat.crumpet.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.garemat.crumpet.MainActivity
import io.github.garemat.crumpet.R
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.net.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Holds the gateway connection open over the VPN so Crumpet's proactive messages arrive as
 *  notifications — no Firebase. The persistent "connected" notification is the visible cost. */
class PresenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The single connection + notification loop. start() can be called many times (pairing
    // changes, START_STICKY redelivery); without this guard each call stacked another WS
    // connection AND another frame collector → duplicate push notifications.
    private var connectionJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        connectionJob?.cancel()  // tear down any prior loop/collector → exactly one connection
        connectionJob = scope.launch {
            val (base, token, _) = Prefs(applicationContext).config()
            launch { Net.maintain(base, token) }
            Net.frames.collect { frame ->
                if (frame.type == "push" && !frame.text.isNullOrBlank()) {
                    postPush(frame.text)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setContentTitle("Crumpet")
            .setContentText("Connected — listening for your nudges")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentIntent(openApp())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun postPush(text: String) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val n = NotificationCompat.Builder(this, PUSH_CHANNEL)
                .setContentTitle("Crumpet")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), n)
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val ONGOING_ID = 1
        const val ONGOING_CHANNEL = "crumpet_presence"
        const val PUSH_CHANNEL = "crumpet_push"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PresenceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceService::class.java))
        }

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(ONGOING_CHANNEL, "Connection",
                    NotificationManager.IMPORTANCE_MIN),
            )
            nm.createNotificationChannel(
                NotificationChannel(PUSH_CHANNEL, "Crumpet messages",
                    NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

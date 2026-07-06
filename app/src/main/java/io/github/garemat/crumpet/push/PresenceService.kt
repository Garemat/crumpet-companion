package io.github.garemat.crumpet.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.garemat.crumpet.MainActivity
import io.github.garemat.crumpet.R
import io.github.garemat.crumpet.data.FileInbox
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.net.Net
import io.github.garemat.crumpet.sync.SyncWorker
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

    // Collapse the reconnect backoff the moment Android says connectivity (e.g. the VPN) is back,
    // instead of waiting out a multi-minute backoff window.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { Net.retryNow() }
    }
    private var callbackRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!callbackRegistered) {
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    .registerDefaultNetworkCallback(networkCallback)
                callbackRegistered = true
            }
        }
        connectionJob?.cancel()  // tear down any prior loop/collector → exactly one connection
        connectionJob = scope.launch {
            val prefs = Prefs(applicationContext)
            val (base, token, _) = prefs.config()
            launch { Net.maintain(base, token, prefs) }
            launch {
                // On each reconnect, kick a health sync (throttled inside syncOnce) rather than
                // waiting up to 3h for the periodic worker — data lands right after downtime.
                var wasConnected = false
                Net.connected.collect { now ->
                    if (now && !wasConnected) SyncWorker.syncOnce(applicationContext)
                    wasConnected = now
                }
            }
            Net.frames.collect { frame ->
                when {
                    frame.type == "push" && !frame.text.isNullOrBlank() -> {
                        frame.pushId?.let { Net.ack(it) }             // confirm receipt
                        postPush(frame.pushId ?: frame.text.hashCode(), frame.text)
                    }
                    frame.type == "dismiss" -> frame.pushId?.let {    // read elsewhere → cancel here
                        NotificationManagerCompat.from(this@PresenceService).cancel(it)
                    }
                    // A file offer with no UI alive: fetch-and-store now (fetching marks it
                    // delivered, so the brain stops re-offering). The ViewModel weaves stored
                    // files into the chat on next open; no notification — the accompanying
                    // push/reply text is the announcement.
                    frame.type == "file" -> frame.fileId?.let { id ->
                        scope.launch {
                            FileInbox.obtain(
                                applicationContext, Prefs(applicationContext),
                                id, frame.name ?: "file", frame.caption ?: "",
                                frame.mime ?: "application/octet-stream",
                            )
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (callbackRegistered) {
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(networkCallback)
            }
            callbackRegistered = false
        }
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

    private fun postPush(notifId: Int, text: String) {
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
            NotificationManagerCompat.from(this).notify(notifId, n)
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

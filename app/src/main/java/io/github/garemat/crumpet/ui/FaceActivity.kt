package io.github.garemat.crumpet.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.garemat.crumpet.R
import io.github.garemat.crumpet.audio.HandsFreeLoop
import io.github.garemat.crumpet.audio.VoiceSession
import io.github.garemat.crumpet.audio.VoiceState
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.net.Net
import kotlinx.coroutines.launch

/** Full-screen Crumpet: a WebView on the brain's own `GET /face` (the SAME animated face
 *  the desk shells use), fed live by the state WS. Leaving the app drops it into native
 *  picture-in-picture — Crumpet floats over Maps/whatever, with a PiP mic action driving
 *  the shared [VoiceSession] — and a tap expands him back. While the face is on screen it
 *  also runs the on-device "hey crumpet" wake word ([HandsFreeLoop]) so you can talk to it
 *  hands-free; the mic is only ever open here, never in the background. Design: car-mode.md. */
class FaceActivity : ComponentActivity() {

    companion object {
        private const val ACTION_PTT = "io.github.garemat.crumpet.PTT"
        // The brain's state WS (GATEWAY_WS_PORT default). The face page connects itself
        // via its ?ws=…&token=… params; auth is the device's GATEWAY_WS_TOKENS entry.
        private const val STATE_WS_PORT = 8800
    }

    private var web: WebView? = null
    private var handsFree: HandsFreeLoop? = null

    private val ptt = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Mic permission is granted from the chat screen's mic button; without it the
            // session surfaces its note there. PiP can't walk a permission dialog.
            VoiceSession.toggle(context)
        }
    }

    private val micPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startHandsFree() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, ptt, IntentFilter(ACTION_PTT),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        // Edge-to-edge, no bars, screen held awake — it's a face on a dash/desk mount.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val view = WebView(this).apply {
            settings.javaScriptEnabled = true   // the face IS a JS animation
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()     // keep navigation in-view
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        web = view
        setContentView(view)

        lifecycleScope.launch {
            val (base, token, _) = Prefs(this@FaceActivity).config()
            val host = Uri.parse(base).host
            if (base.isBlank() || host == null) {
                finish()  // not paired — nothing to show
                return@launch
            }
            view.loadUrl("$base/face?ws=$host:$STATE_WS_PORT/ws&token=$token")
        }

        applyPipParams(VoiceSession.state.value)
        lifecycleScope.launch {
            // Keep the PiP action's icon honest (mic ↔ stop) as the session moves.
            VoiceSession.state.collect { applyPipParams(it) }
        }
    }

    /** "Hey crumpet" is only ever listened for while the face is on screen — that's the
     *  battery deal (no background mic service). onStart wires it up if the mic's granted,
     *  else asks once; onStop tears it down. */
    private fun startHandsFree() {
        if (handsFree != null) return
        val loop = HandsFreeLoop(applicationContext)
        handsFree = loop
        loop.start()
        lifecycleScope.launch {
            // Perk the face up locally the moment the wake word fires — the utterance
            // hasn't reached the brain yet, so nothing else would show "listening".
            loop.listening.collect { on ->
                if (on) web?.evaluateJavascript(
                    "window.crumpetFaceState && window.crumpetFaceState('listening')", null,
                )
            }
        }
    }

    private fun stopHandsFree() {
        handsFree?.stop()
        handsFree = null
    }

    /** Auto-enter on API 31+; onUserLeaveHint covers older devices. */
    private fun applyPipParams(state: VoiceState) {
        val icon = if (state == VoiceState.Recording) R.drawable.ic_pip_stop else R.drawable.ic_pip_mic
        val label = if (state == VoiceState.Recording) "Stop and send" else "Talk to Crumpet"
        val action = RemoteAction(
            Icon.createWithResource(this, icon), label, label,
            PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_PTT).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(1, 1))
            .setActions(listOf(action))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(true) }
            .build()
        setPictureInPictureParams(params)
    }

    // Full-screen face = engaged: the brain parks its workshop so every turn is snappy
    // (car-mode.md — engagement, not location). onStop doesn't fire while in PiP, so a
    // face floating over Maps keeps the workshop parked — exactly the driving case.
    override fun onStart() {
        super.onStart()
        Net.engaged(true)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) startHandsFree()
        else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    override fun onStop() {
        Net.engaged(false)
        stopHandsFree()  // mic released the moment the face leaves the screen
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(PictureInPictureParams.Builder()
                .setAspectRatio(Rational(1, 1)).build())
        }
    }

    override fun onDestroy() {
        stopHandsFree()
        unregisterReceiver(ptt)
        web?.destroy()
        web = null
        super.onDestroy()
    }
}

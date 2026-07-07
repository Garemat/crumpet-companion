package io.github.garemat.crumpet.push

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Process
import android.view.KeyEvent
import io.github.garemat.crumpet.data.InFrame

/** The device-action verb table — the AUTHORITATIVE allowlist (car-mode.md threat model).
 *  The brain only ever sends a verb + plain strings; every URI/intent is built HERE, and
 *  unknown verbs are refused. The brain reads untrusted content, so keep this table
 *  boring — each new verb re-runs the threat paragraph in the design doc. */
object ActionRunner {

    /** Returns whether the action actually ran — the honest bit the ack carries. */
    fun run(context: Context, frame: InFrame): Boolean = when (frame.verb) {
        "navigate" -> navigate(context, frame.query.orEmpty())
        "media" -> media(context, frame.control.orEmpty())
        else -> false  // not in the table — refused, acked ok=false
    }

    private fun navigate(ctx: Context, query: String): Boolean {
        if (query.isBlank()) return false
        // Android silently DROPS background activity starts (API 29+) — don't ack "ran"
        // for an intent the OS binned. Visible (incl. the face in PiP) is good enough.
        if (!appVisible(ctx)) return false
        val q = Uri.encode(query.take(200))
        // Turn-by-turn when a nav app claims google.navigation; geo: covers the rest
        // (Organic Maps, OsmAnd). The query is only ever a place string, never a URI.
        for (uri in listOf("google.navigation:q=$q", "geo:0,0?q=$q")) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return true
            } catch (_: ActivityNotFoundException) {
                // no handler for this scheme — try the next
            }
        }
        return false
    }

    private fun media(ctx: Context, control: String): Boolean {
        val key = when (control) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return false
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return true
    }

    private fun appVisible(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any {
            it.pid == Process.myPid() &&
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        } == true
    }
}

package io.github.garemat.crumpet.push

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Wakes the Spotify app by briefly binding its App Remote service, so this phone shows up
 *  in the brain's Spotify Connect device list (phone-agency.md "minimal tier"). Connect →
 *  Spotify's process is awake → disconnect immediately; the brain's Web API does ALL actual
 *  control. The wake_spotify action carries zero data, and this object sends none — the only
 *  thing that can happen here is Spotify waking up. Don't grow this into playback control:
 *  that path stays brain-side by design. */
object SpotifyWake {

    // The app's public OAuth identifier (not a secret) — same dashboard app the brain uses.
    // The dashboard must list this package + signing SHA-1 and the redirect URI below.
    private const val CLIENT_ID = "65837cfed82a44328985d8fa79dc2f9b"
    private const val REDIRECT_URI = "crumpet://spotify"

    /** Background wake for the wake_spotify action: never shows auth UI, quick timeout so
     *  the honest ack beats the brain's 8s window. */
    suspend fun wake(context: Context): Boolean = connect(context, showAuth = false) == null

    /** One-time setup from the Setup screen: lets Spotify show its approval view.
     *  Returns null on success, else a short message for the UI. */
    suspend fun connect(context: Context, showAuth: Boolean): String? =
        // App Remote wants the main thread; the caller may be on IO (PresenceService).
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(if (showAuth) 60_000 else 6_000) {
                suspendCancellableCoroutine { cont ->
                    val params = ConnectionParams.Builder(CLIENT_ID)
                        .setRedirectUri(REDIRECT_URI)
                        .showAuthView(showAuth)
                        .build()
                    SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                        override fun onConnected(remote: SpotifyAppRemote) {
                            // Connected = the process is awake — that's the whole job.
                            SpotifyAppRemote.disconnect(remote)
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onFailure(error: Throwable) {
                            if (cont.isActive) cont.resume(describe(error))
                        }
                    })
                }
            } ?: "Spotify didn't answer in time"
        }

    private fun describe(e: Throwable): String = when (e) {
        is CouldNotFindSpotifyApp -> "Spotify isn't installed on this phone"
        is NotLoggedInException -> "Open Spotify and log in first"
        is UserNotAuthorizedException ->
            "Crumpet needs approval in Spotify — tap Connect and approve the dialog"
        else -> e.message ?: e.javaClass.simpleName
    }
}

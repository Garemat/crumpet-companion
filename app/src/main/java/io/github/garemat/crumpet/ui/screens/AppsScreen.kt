package io.github.garemat.crumpet.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.ui.AppViewModel
import org.json.JSONObject

/** Crumpet's self-built web apps, inside the companion: a WebView on the brain's app
 *  launcher (`https://crumpet/app/` — the Caddy TLS edge; the hostname is WireGuard-pushed
 *  DNS and the internal-CA root is a user cert, trusted via networkSecurityConfig). The
 *  stored gateway token is injected into the origin's localStorage under `crumpet-token` —
 *  the key every served app reads (the review gate seeds it the same way) — at page start
 *  AND finish, so apps open already authenticated and the user never sees a token prompt.
 *  New apps Crumpet publishes appear in the launcher list with zero companion changes. */

private const val APPS_URL = "https://crumpet/app/"
private const val TOKEN_KEY = "crumpet-token"

private fun inject(view: WebView, token: String) {
    if (token.isBlank()) return
    // JSONObject.quote → a safe JS string literal, whatever the token contains.
    val js = "try{localStorage.setItem('$TOKEN_KEY',${JSONObject.quote(token)})}catch(e){}"
    view.evaluateJavascript(js, null)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppsScreen(vm: AppViewModel) {
    val token by vm.token.collectAsStateWithLifecycle()
    val latestToken = rememberUpdatedState(token)
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    // System back walks the WebView history first (launcher → app → back to launcher).
    BackHandler(enabled = canGoBack) { web?.goBack() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true   // the apps ARE JS
                settings.domStorageEnabled = true   // token + the apps' offline caches
                webViewClient = object : WebViewClient() {
                    // Start usually beats the app's scripts; finish guarantees the key is
                    // there before any user-triggered fetch reads it. Idempotent either way.
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        inject(view, latestToken.value)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        inject(view, latestToken.value)
                        canGoBack = view.canGoBack()
                    }
                }
                web = this
                loadUrl(APPS_URL)
            }
        },
        update = { view ->
            // DataStore can deliver the token after first composition — re-inject then.
            if (token.isNotBlank()) inject(view, token)
        },
    )
}

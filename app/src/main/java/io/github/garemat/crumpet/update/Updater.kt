package io.github.garemat.crumpet.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.garemat.crumpet.net.Net
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub-Releases self-update. Works against a public repo with no auth, or a private repo
 *  with a read-only token (Settings → GitHub token). Mirrors the lunachron updater. */
object Updater {
    private const val REPO = "Garemat/crumpet-companion"
    private const val ASSET = "app-release.apk"

    data class Available(val tag: String, val version: String)

    @Serializable
    private data class Rel(
        @SerialName("tag_name") val tagName: String,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        val url: String,                                  // API URL (works for private + public)
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    )

    private fun io.ktor.client.request.HttpRequestBuilder.gh(token: String, accept: String) {
        if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, accept)
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    /** The latest release if it's newer than [current] (the installed versionName), else null. */
    suspend fun check(current: String, token: String): Available? = runCatching {
        val rel: Rel = Net.client.get("https://api.github.com/repos/$REPO/releases/latest") {
            gh(token, "application/vnd.github+json")
        }.body()
        val latest = rel.tagName.removePrefix("v")
        if (isNewer(latest, current.removePrefix("v"))) Available(rel.tagName, latest) else null
    }.getOrNull()

    /** Download the release APK to the cache dir, reporting [0,1] progress. Uses HttpURLConnection
     *  for the binary (deterministic redirect handling for private-repo assets), and validates the
     *  result is really an APK so a bad download fails loudly instead of "problem with the app file". */
    suspend fun download(context: Context, tag: String, token: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val rel: Rel = Net.client.get("https://api.github.com/repos/$REPO/releases/tags/$tag") {
                gh(token, "application/vnd.github+json")
            }.body()
            val asset = rel.assets.firstOrNull { it.name == ASSET }
                ?: error("No $ASSET in release $tag")

            val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                instanceFollowRedirects = true   // follow to the signed asset URL (needs no auth)
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            val code = conn.responseCode
            if (code !in 200..299) error("Download failed (HTTP $code)")
            val total = conn.contentLengthLong
            val out = File(context.cacheDir, "crumpet-update.apk")
            conn.inputStream.use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            // Validate: a real APK is a zip → starts with "PK". Anything else (an error page) is rejected.
            val ok = out.length() > 100_000L && out.inputStream().use { it.read() == 0x50 && it.read() == 0x4B }
            if (!ok) {
                out.delete()
                error("Downloaded file isn't a valid APK (got ${out.length()} bytes)")
            }
            out
        }

    /** Hand the APK to the system installer (the user confirms). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** True if semver [latest] > [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }
}

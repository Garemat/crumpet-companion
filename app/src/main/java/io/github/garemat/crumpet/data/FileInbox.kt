package io.github.garemat.crumpet.data

import android.content.Context
import io.github.garemat.crumpet.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Receives the files Crumpet sends (charts, progress photos, app previews).
 *
 *  A {"type":"file"} frame is only an OFFER — the bytes come from the brain's token-authed
 *  GET /file/<id>, and fetching marks them delivered so the brain stops re-offering. Both
 *  frame collectors route here (the ViewModel when the UI is open, PresenceService when the
 *  socket is alive without a UI), so the fetch is deduped behind one mutex: whoever loses the
 *  race still gets the stored file back and can render it. */
object FileInbox {
    private val lock = Mutex()

    /** Fetch-and-store an offered file (or return it if already stored). Null = fetch failed —
     *  fine to drop; the brain re-offers unfetched files on the next reconnect. */
    suspend fun obtain(
        context: Context, prefs: Prefs,
        id: String, name: String, caption: String, mime: String,
    ): InboxFile? = lock.withLock {
        prefs.inboxFiles().firstOrNull { it.id == id }?.let { return it }
        val (base, token, _) = prefs.config()
        if (base.isBlank() || token.isBlank()) return null
        val bytes = withContext(Dispatchers.IO) {
            runCatching { Net.fetchFile(base, token, id) }.getOrNull()
        } ?: return null
        val path = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "inbox").apply { mkdirs() }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
                File(dir, "$id-$safe").apply { writeBytes(bytes) }.absolutePath
            }.getOrNull()
        } ?: return null
        val file = InboxFile(id, name, caption, mime, path, System.currentTimeMillis())
        prefs.addInboxFile(file)
        file
    }
}

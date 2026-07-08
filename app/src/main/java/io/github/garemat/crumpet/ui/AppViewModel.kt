package io.github.garemat.crumpet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.garemat.crumpet.data.AgendaItem
import io.github.garemat.crumpet.data.ChatLine
import io.github.garemat.crumpet.data.FileInbox
import io.github.garemat.crumpet.data.HealthSnapshot
import io.github.garemat.crumpet.data.InFrame
import io.github.garemat.crumpet.data.InboxFile
import io.github.garemat.crumpet.data.Prefs
import io.github.garemat.crumpet.data.QueuedMsg
import io.github.garemat.crumpet.data.SentImage
import io.github.garemat.crumpet.audio.VoiceSession
import io.github.garemat.crumpet.health.CalendarRepo
import io.github.garemat.crumpet.health.HealthRepo
import io.github.garemat.crumpet.net.Net
import io.github.garemat.crumpet.push.SpotifyWake
import io.github.garemat.crumpet.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val health = HealthRepo(app)
    val calendar = CalendarRepo(app)

    val serverUrl: StateFlow<String> =
        prefs.serverUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val token: StateFlow<String> =
        prefs.token.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val connected: StateFlow<Boolean> = Net.connected
    val status: StateFlow<String> = Net.status

    private val _snapshot = MutableStateFlow(HealthSnapshot())
    val snapshot = _snapshot.asStateFlow()
    private val _agenda = MutableStateFlow<List<AgendaItem>>(emptyList())
    val agenda = _agenda.asStateFlow()
    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat = _chat.asStateFlow()
    private val _thinking = MutableStateFlow(false)
    val thinking = _thinking.asStateFlow()
    // "Currently working on X" — curated milestones from the brain during long turns (sandbox
    // builds etc.). Broadcast to ALL clients, so it shows even for turns started on Discord/voice
    // — independent of [thinking], which only tracks THIS device's own in-flight message.
    private val _activity = MutableStateFlow<String?>(null)
    val activity = _activity.asStateFlow()
    private val _syncMsg = MutableStateFlow<String?>(null)
    val syncMsg = _syncMsg.asStateFlow()

    private val _spotifyMsg = MutableStateFlow<String?>(null)
    val spotifyMsg = _spotifyMsg.asStateFlow()
    // PTT lives in the app-scoped VoiceSession (the face activity's PiP action shares it).
    val voiceState = VoiceSession.state
    val voiceNote = VoiceSession.note

    // Proactive pushes shown but not yet marked read (read = user saw them in chat → dismiss elsewhere).
    private val pendingReads = mutableSetOf<Int>()
    private var chatActive = false

    // self-update
    val ghToken: StateFlow<String> = prefs.ghToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val _update = MutableStateFlow<Updater.Available?>(null)
    val update = _update.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()
    private val _updateError = MutableStateFlow<String?>(null)
    val updateError = _updateError.asStateFlow()
    val currentVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.0.0"
    }.getOrDefault("0.0.0")

    val paired: Boolean get() = serverUrl.value.isNotBlank() && token.value.isNotBlank()

    init {
        viewModelScope.launch { Net.frames.collect(::handleFrame) }
        viewModelScope.launch { Net.sent.collect(::markDelivered) }
        viewModelScope.launch {
            // If the WS drops mid-task we may miss the clear frame; the brain replays the LIVE
            // banner on reconnect but never re-sends a clear — so drop it ourselves on disconnect.
            Net.connected.collect { up -> if (!up) _activity.value = null }
        }
        viewModelScope.launch { loadChatCache() }
        refresh()
        checkForUpdate()
    }

    /** Show the cached conversation immediately (offline-friendly open); the live `history`
     *  frame replaces it when the WS connects. Pending flags are reconciled against the real
     *  outbox — a line whose outbox entry is gone was delivered while this ViewModel was away. */
    private suspend fun loadChatCache() {
        val cached = prefs.chatCache()
        if (cached.isEmpty() || _chat.value.isNotEmpty()) return
        val stillQueued = prefs.outbox().map { it.id }.toSet()
        val reconciled = cached.map { line ->
            if (line.pending && line.id !in stillQueued) line.copy(pending = false) else line
        }
        // Files PresenceService fetched while no UI was alive aren't in the cache yet — and the
        // WS may already be connected (no reconnect → no history frame coming). Tail-append
        // those, but ONLY ones newer than the cache's newest user line (id = epoch millis) so
        // old files the 100-line cache evicted don't resurface at the bottom on every open.
        val known = reconciled.mapNotNull { it.fileId }.toSet()
        val cutoff = reconciled.maxOfOrNull { it.id } ?: 0L
        val missed = prefs.inboxFiles().filter { it.id !in known && it.ts > cutoff }.sortedBy { it.ts }
        // Don't clobber a history frame that raced us while we were reading the cache.
        if (_chat.value.isEmpty()) _chat.value = reconciled + missed.map { it.toChatLine() }
    }

    /** Update the chat state AND persist it, so the conversation survives brain/VPN downtime. */
    private fun updateChat(transform: (List<ChatLine>) -> List<ChatLine>) {
        _chat.update(transform)
        val snapshot = _chat.value
        viewModelScope.launch { prefs.setChatCache(snapshot) }
    }

    /** An outbox entry was written to the live socket — clear its bubble's pending mark. */
    private fun markDelivered(id: Long) {
        updateChat { lines -> lines.map { if (it.id == id) it.copy(pending = false) else it } }
    }

    fun checkForUpdate() = viewModelScope.launch {
        val tok = prefs.ghToken.first()
        _update.value = withContext(Dispatchers.IO) { Updater.check(currentVersion, tok) }
    }

    fun setGhToken(value: String) = viewModelScope.launch { prefs.setGhToken(value) }

    fun downloadAndInstall(context: android.content.Context) = viewModelScope.launch {
        val avail = _update.value ?: return@launch
        val tok = prefs.ghToken.first()
        _updateError.value = null
        _downloadProgress.value = 0f
        runCatching {
            val apk = withContext(Dispatchers.IO) {
                Updater.download(context, avail.tag, tok) { _downloadProgress.value = it }
            }
            Updater.install(context, apk)
        }.onFailure { e -> _updateError.value = "Update failed: ${e.message?.take(100) ?: "unknown error"}" }
        _downloadProgress.value = null
    }

    fun refresh() = viewModelScope.launch {
        _snapshot.value = withContext(Dispatchers.IO) { runCatching { health.todaySnapshot() }.getOrDefault(HealthSnapshot()) }
        _agenda.value = withContext(Dispatchers.IO) { runCatching { calendar.upcoming(7) }.getOrDefault(emptyList()) }
    }

    fun syncNow() = viewModelScope.launch {
        _syncMsg.value = "Syncing…"
        val msg = withContext(Dispatchers.IO) {
            val (base, tok, _) = prefs.config()
            when {
                base.isBlank() || tok.isBlank() -> "Not paired — set the brain URL + token in Connect first."
                !health.available -> "Health Connect isn't available on this device."
                !health.hasAnyPermission() -> "No Health Connect permissions granted — tap Grant above."
                else -> runCatching {
                    // Per-type sync: run with what's granted, and NAME what isn't — a missing
                    // grant should be visible here, not a silent gap in the data.
                    val missing = health.missingTypes()
                    val note = if (missing.isEmpty()) ""
                               else " Not synced (permission off): ${missing.joinToString(", ")} — tap Grant above."
                    val records = health.recentRecords(30)
                    if (records.isEmpty())
                        "Read 0 records from Health Connect — nothing's being written there yet (check the source apps, e.g. MyFitnessPal → Health Connect).$note"
                    else {
                        val c = Net.ingest(base, tok, records).counts
                        val parts = listOf("nutrition", "weight", "steps", "sleep", "workouts")
                            .mapNotNull { k -> c[k]?.takeIf { it > 0 }?.let { "$it $k" } }
                        "Synced ${records.size} record(s)" +
                            (if (parts.isEmpty()) " (all up to date)." else ": ${parts.joinToString(", ")}.") + note
                    }
                }.getOrElse { e -> "Sync failed: ${e.message?.take(90) ?: "unknown error"}" }
            }
        }
        _syncMsg.value = msg
        refresh()
    }

    fun toggleVoice() = VoiceSession.toggle(getApplication())

    /** One-time Spotify App Remote approval (Setup screen) — after this, the brain's
     *  wake_spotify action can wake Spotify silently in the background. */
    fun connectSpotify() = viewModelScope.launch {
        _spotifyMsg.value = "Connecting to Spotify…"
        val err = SpotifyWake.connect(getApplication(), showAuth = true)
        _spotifyMsg.value = err
            ?: "Connected — Crumpet can now wake Spotify on this phone."
    }

    fun sendChat(text: String) {
        if (text.isBlank()) return
        val id = System.currentTimeMillis()
        updateChat { it + ChatLine(fromCrumpet = false, text = text, id = id, pending = true) }
        viewModelScope.launch {
            prefs.addToOutbox(QueuedMsg(id, text, id))  // persist FIRST …
            Net.notifyQueued()                          // … then wake the sender
        }
    }

    /** Send a picked file (photo / PDF / doc) to /attach with optional caption, and show the reply.
     *  Images are also copied into app-local storage so their thumbnail shows in chat (never synced
     *  from the brain). The local copy is keyed by the exact user-text the brain echoes in history,
     *  so the thumbnail re-attaches after a reconnect/history reload. */
    fun sendAttachment(uri: android.net.Uri, caption: String) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val resolver = ctx.contentResolver
        val name = queryName(ctx, uri)
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val isImage = mime.startsWith("image/")

        val bytes = withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        if (bytes == null) {
            updateChat { it + ChatLine(true, "Couldn't read that file, sorry.") }
            return@launch
        }

        var localPath: String? = null
        if (isImage) {
            localPath = withContext(Dispatchers.IO) { saveLocalImage(ctx, bytes) }
            // Marker = exactly what the brain logs for this turn, so history reload can re-match.
            val marker = if (caption.isBlank()) "[+1 image(s)]" else "$caption [+1 image(s)]"
            localPath?.let { prefs.addSentImage(SentImage(marker, it, System.currentTimeMillis())) }
        }

        updateChat {
            it + ChatLine(
                fromCrumpet = false,
                text = if (isImage) caption else (caption.ifBlank { "Sent" }) + " 📎 $name",
                imageUri = localPath,
            )
        }
        _thinking.value = true
        val reply = withContext(Dispatchers.IO) {
            runCatching {
                val (base, tok, _) = prefs.config()
                Net.attach(base, tok, caption, name, mime, bytes).reply
            }.getOrElse { e -> "Couldn't send that attachment: ${e.message?.take(80) ?: "error"}" }
        }
        _thinking.value = false
        updateChat { it + ChatLine(true, reply) }
    }

    private fun saveLocalImage(ctx: android.content.Context, bytes: ByteArray): String? = runCatching {
        val dir = java.io.File(ctx.filesDir, "sent").apply { mkdirs() }
        val f = java.io.File(dir, "${java.util.UUID.randomUUID()}.img")
        f.writeBytes(bytes)
        f.absolutePath
    }.getOrNull()

    /** sqlite datetime('now') from conversation_log — UTC "YYYY-MM-DD HH:MM:SS" → epoch millis. */
    private fun parseTs(ts: String?): Long? = runCatching {
        java.time.LocalDateTime.parse(ts!!.trim().replace(" ", "T"))
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()

    /** Copy a received file into the system Downloads (tap action on non-image file bubbles;
     *  images are already visible inline). MediaStore.Downloads needs API 29+. */
    fun saveToDownloads(line: ChatLine) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val toast: (String) -> Unit = { msg ->
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            toast("Saving needs Android 10+"); return@launch
        }
        val src = line.imageUri
            ?: prefs.inboxFiles().firstOrNull { it.id == line.fileId }?.path
            ?: run { toast("That file isn't on this device any more."); return@launch }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, line.fileName ?: "crumpet-file")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE,
                        line.mime ?: "application/octet-stream")
                }
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                ) ?: return@runCatching false
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    java.io.File(src).inputStream().use { it.copyTo(out) }
                } != null
            }.getOrDefault(false)
        }
        toast(if (ok) "Saved to Downloads" else "Couldn't save the file")
    }

    private fun queryName(ctx: android.content.Context, uri: android.net.Uri): String {
        var name = "file"
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
            }
        }
        return name
    }

    fun savePairing(url: String, tokenValue: String) = viewModelScope.launch {
        prefs.setPairing(url, tokenValue)
    }

    /** Chat screen visibility — when visible, proactive pushes are immediately "read". */
    fun setChatActive(active: Boolean) {
        chatActive = active
        if (active) flushReads()
    }

    private fun flushReads() {
        if (pendingReads.isEmpty()) return
        val nm = androidx.core.app.NotificationManagerCompat.from(getApplication())
        pendingReads.forEach { id -> Net.read(id); nm.cancel(id) }  // dismiss elsewhere + locally
        pendingReads.clear()
    }

    private suspend fun handleFrame(f: InFrame) {
        when (f.type) {
            "reply" -> f.text?.let { t -> updateChat { it + ChatLine(true, t) } }
            "push" -> f.text?.let { t ->
                updateChat { it + ChatLine(true, t) }
                f.pushId?.let { id -> if (chatActive) { Net.read(id) } else pendingReads.add(id) }
            }
            // A file offer (chart/photos/preview). Fetch-and-store via the shared inbox
            // (PresenceService may win the race; either way we get the stored file back),
            // then show it — unless it's a re-offer of something already in the thread.
            "file" -> f.fileId?.let { id ->
                viewModelScope.launch {
                    val file = FileInbox.obtain(
                        getApplication(), prefs,
                        id, f.name ?: "file", f.caption ?: "", f.mime ?: "application/octet-stream",
                    ) ?: return@launch  // fetch failed — the brain re-offers on next reconnect
                    updateChat { lines ->
                        if (lines.any { it.fileId == file.id }) lines else lines + file.toChatLine()
                    }
                }
            }
            "state" -> _thinking.value = f.value == "thinking"
            "activity" -> _activity.value = f.text?.takeIf { it.isNotBlank() }  // null/blank clears
            // A turn finished on ANOTHER channel (Discord/voice/another device) — append it live
            // so the one thread stays current without waiting for a reconnect's history sync.
            // (The brain never sends us our own turns: the asker only gets `reply`.)
            "exchange" -> updateChat { lines ->
                var out = lines
                f.user?.takeIf { it.isNotBlank() }?.let { out = out + ChatLine(false, it, f.source) }
                f.reply?.takeIf { it.isNotBlank() }?.let { out = out + ChatLine(true, it, f.source) }
                out
            }
            "history" -> f.messages?.let { hs ->
                // Re-attach locally-stored image thumbnails to the user's image-send lines (the
                // brain echoes them as "<caption> [+N image(s)]"). Match each stored image once,
                // and strip the marker for display so it reads as a clean caption + thumbnail.
                val sent = prefs.sentImages().toMutableList()
                val fromServer = hs.map { m ->
                    val crumpet = m.role == "crumpet"
                    if (!crumpet && "[+" in m.text && "image(s)]" in m.text) {
                        val i = sent.indexOfFirst { it.marker == m.text }
                        if (i >= 0) {
                            val img = sent.removeAt(i)
                            val cleaned = m.text.substringBeforeLast(" [+").substringBeforeLast("[+").trim()
                            return@map ChatLine(false, cleaned, m.source, imageUri = img.path)
                        }
                    }
                    ChatLine(crumpet, m.text, m.source)
                }
                // Received files are app-local too (history can't carry them) — weave them back
                // in by time. Server ts is sqlite UTC "YYYY-MM-DD HH:MM:SS"; files older than
                // the history window stay out (they've scrolled away with their conversation).
                val serverTs = hs.map { parseTs(it.ts) }
                val cutoff = serverTs.firstOrNull { it != null }
                val files = prefs.inboxFiles()
                    .filter { cutoff == null || it.ts >= cutoff }.sortedBy { it.ts }
                val merged = mutableListOf<ChatLine>()
                var fi = 0
                fromServer.forEachIndexed { i, line ->
                    val ts = serverTs[i]
                    while (fi < files.size && ts != null && files[fi].ts <= ts) {
                        merged += files[fi++].toChatLine()
                    }
                    merged += line
                }
                while (fi < files.size) merged += files[fi++].toChatLine()
                // Server history is the truth, but it can't know about messages still queued in
                // the outbox — keep those visible (pending) at the tail instead of dropping them.
                val stillQueued = prefs.outbox().map { it.id }.toSet()
                val queuedLines = _chat.value.filter { it.pending && it.id in stillQueued }
                updateChat { merged + queuedLines }
            }
        }
    }
}

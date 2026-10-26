package io.github.garemat.crumpet.gym

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Sync bookkeeping + the last thing worth telling the user ("Logged 18 sets… PRs: …",
 *  or why a write was refused). */
@Serializable
data class GymMeta(
    val libraryEtag: String = "",
    val lastSyncMillis: Long = 0,
    val note: String = "",
)

/** The gym screen's offline store: one JSON file per concern under filesDir/gym, each
 *  written atomically (temp + rename, so a kill mid-write leaves the old file whole), all
 *  behind one mutex. Not Room: every /gym document is whole-document by design (a routine,
 *  the state snapshot, a session), so there's nothing to query — just load, replace, save.
 *
 *  - state.json   — the brain's /gym/state snapshot, plus local optimistic edits
 *  - library.json — the exercise library (~800 KB, fetched once per ETag)
 *  - active.json  — the in-progress session, saved on EVERY change
 *  - outbox.json  — queued writes, drained in order by GymSync
 *  - rejected.json — sessions the brain REFUSED (400): never silently lost — a refusal
 *                    would most likely be a contract bug, and a workout is unrepeatable
 *  Flows are hot so the UI recomposes on any change, from the screen or the sync. */
class GymStore internal constructor(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()

    private val _state = MutableStateFlow(read("state.json") ?: GymState())
    private val _active = MutableStateFlow(read<ActiveSession>("active.json"))
    private val _outbox = MutableStateFlow(read("outbox.json") ?: emptyList<GymOutboxItem>())
    private val _meta = MutableStateFlow(read("meta.json") ?: GymMeta())
    private val _library = MutableStateFlow<Library?>(null)

    val state: StateFlow<GymState> = _state.asStateFlow()
    val active: StateFlow<ActiveSession?> = _active.asStateFlow()
    val outbox: StateFlow<List<GymOutboxItem>> = _outbox.asStateFlow()
    val meta: StateFlow<GymMeta> = _meta.asStateFlow()

    /** Loaded lazily — it's big and only the exercise picker needs it. */
    suspend fun library(): Library = lock.withLock {
        _library.value ?: (read<Library>("library.json") ?: Library()).also { _library.value = it }
    }

    suspend fun saveState(s: GymState) = lock.withLock { _state.value = s; write("state.json", s) }

    suspend fun updateState(f: (GymState) -> GymState) = lock.withLock {
        val s = f(_state.value); _state.value = s; write("state.json", s)
    }

    suspend fun saveLibrary(lib: Library, etag: String) = lock.withLock {
        _library.value = lib
        write("library.json", lib)
        _meta.value = _meta.value.copy(libraryEtag = etag); write("meta.json", _meta.value)
    }

    suspend fun setActive(s: ActiveSession?) = lock.withLock {
        _active.value = s
        if (s == null) File(dir, "active.json").delete() else write("active.json", s)
    }

    suspend fun enqueue(method: String, path: String, body: String = "", label: String = "") =
        lock.withLock {
            val id = (_outbox.value.maxOfOrNull { it.id } ?: 0) + 1
            _outbox.value = _outbox.value + GymOutboxItem(id, method, path, body, label)
            write("outbox.json", _outbox.value)
        }

    suspend fun removeFromOutbox(id: Long) = lock.withLock {
        _outbox.value = _outbox.value.filterNot { it.id == id }; write("outbox.json", _outbox.value)
    }

    suspend fun keepRejected(item: GymOutboxItem) = lock.withLock {
        val kept = (read<List<GymOutboxItem>>("rejected.json") ?: emptyList()) + item
        write("rejected.json", kept.takeLast(20))
    }

    suspend fun setMeta(f: (GymMeta) -> GymMeta) = lock.withLock {
        _meta.value = f(_meta.value); write("meta.json", _meta.value)
    }

    private inline fun <reified T> read(name: String): T? = runCatching {
        File(dir, name).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<T>(it) }
    }.getOrNull()

    private inline fun <reified T> write(name: String, value: T) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(value))
        if (!tmp.renameTo(File(dir, name))) {  // rename-over is atomic on the same fs
            File(dir, name).delete(); tmp.renameTo(File(dir, name))
        }
    }

    companion object {
        @Volatile private var instance: GymStore? = null

        /** One store per process — the screen, the worker and the timer share its flows. */
        fun get(context: Context): GymStore = instance ?: synchronized(this) {
            instance ?: GymStore(File(context.applicationContext.filesDir, "gym")).also { instance = it }
        }
    }
}

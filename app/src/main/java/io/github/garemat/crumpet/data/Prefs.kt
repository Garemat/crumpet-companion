package io.github.garemat.crumpet.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "crumpet")

/** Pairing config + per-type sync watermarks. No brain secrets live here beyond the gateway token. */
class Prefs(private val context: Context) {
    private val URL = stringPreferencesKey("server_url")
    private val TOKEN = stringPreferencesKey("token")
    private val GH_TOKEN = stringPreferencesKey("gh_token")  // optional, for self-update on a private repo
    private val WATERMARK = longPreferencesKey("sync_watermark")  // epoch millis; read HC since this
    private val SENT_IMAGES = stringPreferencesKey("sent_images")  // JSON list of SentImage (local only)
    private val json = Json { ignoreUnknownKeys = true }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[URL] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[TOKEN] ?: "" }
    val ghToken: Flow<String> = context.dataStore.data.map { it[GH_TOKEN] ?: "" }
    val watermark: Flow<Long> = context.dataStore.data.map { it[WATERMARK] ?: 0L }

    suspend fun setPairing(url: String, token: String) {
        context.dataStore.edit { it[URL] = url.trim().trimEnd('/'); it[TOKEN] = token.trim() }
    }

    suspend fun setGhToken(value: String) {
        context.dataStore.edit { it[GH_TOKEN] = value.trim() }
    }

    suspend fun setWatermark(epochMillis: Long) {
        context.dataStore.edit { it[WATERMARK] = epochMillis }
    }

    /** (serverUrl, token, watermark) read once — for the sync worker / one-shot calls. */
    suspend fun config(): Triple<String, String, Long> {
        val p = context.dataStore.data.first()
        return Triple(p[URL] ?: "", p[TOKEN] ?: "", p[WATERMARK] ?: 0L)
    }

    // --- locally-stored sent images (thumbnails survive history reloads) ---
    suspend fun sentImages(): List<SentImage> {
        val raw = context.dataStore.data.first()[SENT_IMAGES] ?: return emptyList()
        return runCatching { json.decodeFromString<List<SentImage>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun addSentImage(img: SentImage) {
        val list = (sentImages() + img).takeLast(200)  // cap so it can't grow forever
        context.dataStore.edit { it[SENT_IMAGES] = json.encodeToString(list) }
    }
}

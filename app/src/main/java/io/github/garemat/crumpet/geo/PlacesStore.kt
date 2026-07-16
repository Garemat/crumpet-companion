package io.github.garemat.crumpet.geo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A user-defined geofence. The coordinates in here are the most sensitive data the app
 *  holds ("where is Home") — they NEVER leave the phone (only labels cross the wire) and
 *  are encrypted at rest with a Keystore key (see [SealedBox]), so neither a device backup
 *  nor a filesystem read of the DataStore file exposes them. */
@Serializable
data class Place(val label: String, val lat: Double, val lng: Double, val radiusM: Int = 150)

/** One presence event queued for the brain — labels + town only, by design. */
@Serializable
data class PresenceEvent(val state: String, val label: String, val area: String? = null, val at: String)

private val Context.geoStore by preferencesDataStore(name = "crumpet-geo")

/** Presence state: the encrypted places list, the pause switch, the last known label
 *  (for transition synthesis) and the event outbox (same persisted-outbox model as chat/
 *  calendar — a dead tunnel queues, reconnect drains in order). */
class PlacesStore(private val context: Context) {
    private val PLACES_ENC = stringPreferencesKey("places_enc") // SealedBox ciphertext
    private val PAUSED = booleanPreferencesKey("presence_paused")
    private val LAST_LABEL = stringPreferencesKey("presence_last_label") // "" = away/unknown
    private val OUTBOX = stringPreferencesKey("presence_outbox")
    private val json = Json { ignoreUnknownKeys = true }

    val paused: Flow<Boolean> = context.geoStore.data.map { it[PAUSED] ?: false }

    suspend fun isPaused(): Boolean = context.geoStore.data.first()[PAUSED] ?: false

    suspend fun setPaused(value: Boolean) {
        context.geoStore.edit { it[PAUSED] = value }
    }

    suspend fun places(): List<Place> {
        val enc = context.geoStore.data.first()[PLACES_ENC] ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<Place>>(SealedBox.open(enc))
        }.getOrDefault(emptyList())
    }

    /** Exposed for the Places screen; decrypts on each emission (tiny payload, rare writes). */
    val placesFlow: Flow<List<Place>> = context.geoStore.data.map { p ->
        p[PLACES_ENC]?.let { enc ->
            runCatching { json.decodeFromString<List<Place>>(SealedBox.open(enc)) }
                .getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun setPlaces(places: List<Place>) {
        val enc = SealedBox.seal(json.encodeToString(places))
        context.geoStore.edit { it[PLACES_ENC] = enc }
    }

    suspend fun lastLabel(): String = context.geoStore.data.first()[LAST_LABEL] ?: ""

    suspend fun setLastLabel(label: String) {
        context.geoStore.edit { it[LAST_LABEL] = label }
    }

    // --- event outbox: single-edit{} RMW like the chat/calendar outboxes, so a worker
    // append and a drain remove can't lose each other's update ---

    suspend fun outbox(): List<PresenceEvent> = decode(context.geoStore.data.first()[OUTBOX])

    suspend fun addToOutbox(events: List<PresenceEvent>) {
        if (events.isEmpty()) return
        context.geoStore.edit { p ->
            p[OUTBOX] = json.encodeToString((decode(p[OUTBOX]) + events).takeLast(50))
        }
    }

    suspend fun removeFromOutbox(sent: List<PresenceEvent>) {
        if (sent.isEmpty()) return
        context.geoStore.edit { p ->
            val remaining = decode(p[OUTBOX]).toMutableList()
            sent.forEach { remaining.remove(it) }
            p[OUTBOX] = json.encodeToString(remaining)
        }
    }

    private fun decode(raw: String?): List<PresenceEvent> = raw?.let {
        runCatching { json.decodeFromString<List<PresenceEvent>>(it) }.getOrDefault(emptyList())
    } ?: emptyList()
}

/** AES-GCM via the Android Keystore — the key never leaves secure hardware, so the
 *  ciphertext is useless off-device (and `allowBackup=false` keeps it off the cloud
 *  anyway; this is the second layer). Payload format: base64(iv) + ":" + base64(ct). */
private object SealedBox {
    private const val ALIAS = "crumpet_places"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val (iv, ct) = sealed.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}

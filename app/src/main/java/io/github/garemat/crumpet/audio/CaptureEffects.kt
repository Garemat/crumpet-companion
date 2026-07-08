package io.github.garemat.crumpet.audio

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/** Platform echo-cancel + noise-suppress on a capture session. The wake word drowns while
 *  Spotify plays on the phone's own speaker (the mic hears mostly music) — AEC subtracts the
 *  device's OWN playback from the capture path, which is exactly that case. Availability is
 *  per-device; we attach what exists and log the outcome so `adb logcat -s VoiceTiming`
 *  shows whether this phone actually got the help. */
object CaptureEffects {

    /** Attach AEC + NS to the record's session. Release the returned effects with the record. */
    fun attach(record: AudioRecord, tag: String): List<AudioEffect> {
        val effects = mutableListOf<AudioEffect>()
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.let {
                it.enabled = true
                effects += it
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.let {
                it.enabled = true
                effects += it
            }
        }
        Log.d(
            "VoiceTiming",
            "$tag capture effects: " + (effects.joinToString { it.descriptor.name }
                .ifEmpty { "none available" }),
        )
        return effects
    }

    fun release(effects: List<AudioEffect>) {
        effects.forEach { runCatching { it.release() } }
    }
}

package io.github.garemat.crumpet.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wrap raw 16 kHz mono 16-bit little-endian PCM in a WAV container — the format the
 *  brain's /voice endpoint decodes. Shared by push-to-talk and the hands-free loop. */
fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(36 + pcm.size)
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16)                   // PCM fmt chunk size
    header.putShort(1)                  // PCM
    header.putShort(1)                  // mono
    header.putInt(sampleRate)
    header.putInt(sampleRate * 2)       // byte rate
    header.putShort(2)                  // block align
    header.putShort(16)                 // bits per sample
    header.put("data".toByteArray())
    header.putInt(pcm.size)
    return header.array() + pcm
}

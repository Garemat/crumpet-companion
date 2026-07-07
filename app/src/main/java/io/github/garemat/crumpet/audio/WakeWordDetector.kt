package io.github.garemat.crumpet.audio

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** On-device "hey crumpet" — a faithful Kotlin port of the shell's wyoming-openwakeword
 *  pipeline (three chained tflite models, bundled from the same files the desk shell
 *  runs). Constants are pinned to the shell's: 1280-sample (80ms) chunks over a
 *  1760-sample mel window, the `mels/10 + 2` transform, a 76×32 mel window per
 *  embedding stepping 8 frames, and hey_crumpet over the last 16 embeddings with
 *  threshold 0.5 / trigger level 1. Audio is RAW int16 values as float32 — the models
 *  were trained unnormalised; don't "fix" the scaling.
 *
 *  Detection never leaves the device: audio is only shipped to the brain after this
 *  fires (the same rule the satellites follow). */
class WakeWordDetector(context: Context, private val threshold: Float = 0.5f) {

    companion object {
        const val CHUNK_SAMPLES = 1280          // 80 ms @ 16 kHz — feed exactly this many
        private const val MEL_WINDOW = 1760     // chunk + 480 look-back samples
        private const val MEL_FRAMES_PER_CHUNK = 8
        private const val EMB_FRAMES = 76       // 775 ms of mel frames per embedding
        private const val NUM_MELS = 32
        private const val WW_EMBEDDINGS = 16    // hey_crumpet input: [1, 16, 96]
        private const val EMB_DIM = 96
        private const val REFRACTORY_CHUNKS = 25  // ~2 s of deafness after a detection
    }

    // NB: the bundled melspectrogram.tflite is the shell's model with its input shape patched
    // from the dynamic [1,-1] to a fixed [1,1760]. Android's TFLite allocates tensors at
    // CONSTRUCTION (unlike desktop Python, which is lazy), and the dynamic default [1,1]
    // overflows CONV_2D before any resizeInput can run — it crashed the app. The patch is a
    // shape-only flatbuffer edit (weights untouched; output verified bit-identical). The
    // resize below is now a no-op but harmless.
    private val melspec = interpreter(context, "oww/melspectrogram.tflite").apply {
        resizeInput(0, intArrayOf(1, MEL_WINDOW))
        allocateTensors()
    }
    private val embedding = interpreter(context, "oww/embedding_model.tflite").apply {
        resizeInput(0, intArrayOf(1, EMB_FRAMES, NUM_MELS, 1))
        allocateTensors()
    }
    private val wake = interpreter(context, "oww/hey_crumpet.tflite")

    // Rolling windows, zero-primed like the shell's autofill.
    private val audioWindow = FloatArray(MEL_WINDOW)
    private val melWindow = Array(EMB_FRAMES) { FloatArray(NUM_MELS) }
    private val embWindow = Array(WW_EMBEDDINGS) { FloatArray(EMB_DIM) }
    private var melFrames = 0
    private var embCount = 0
    private var deafFor = 0

    // Reused inference tensors (the chunk cadence is fixed, so shapes never change).
    private val melIn = Array(1) { FloatArray(MEL_WINDOW) }
    private val melOut = Array(1) { Array(1) { Array(MEL_FRAMES_PER_CHUNK) { FloatArray(NUM_MELS) } } }
    private val embIn = Array(1) { Array(EMB_FRAMES) { Array(NUM_MELS) { FloatArray(1) } } }
    private val embOut = Array(1) { Array(1) { Array(1) { FloatArray(EMB_DIM) } } }
    private val wakeIn = Array(1) { Array(WW_EMBEDDINGS) { FloatArray(EMB_DIM) } }
    private val wakeOut = Array(1) { FloatArray(1) }

    /** Feed one 1280-sample chunk; true = "hey crumpet" heard. */
    fun accept(chunk: ShortArray): Boolean {
        require(chunk.size == CHUNK_SAMPLES) { "feed ${CHUNK_SAMPLES}-sample chunks" }
        if (deafFor > 0) {
            deafFor--
            return false
        }

        // Slide the audio window and mel-ify the newest 80 ms (with its look-back).
        System.arraycopy(audioWindow, CHUNK_SAMPLES, audioWindow, 0, MEL_WINDOW - CHUNK_SAMPLES)
        for (i in 0 until CHUNK_SAMPLES) {
            audioWindow[MEL_WINDOW - CHUNK_SAMPLES + i] = chunk[i].toFloat()
        }
        System.arraycopy(audioWindow, 0, melIn[0], 0, MEL_WINDOW)
        melspec.run(melIn, melOut)

        // Slide the mel window up 8 frames, transformed to fit the embedding model.
        for (r in 0 until EMB_FRAMES - MEL_FRAMES_PER_CHUNK) {
            System.arraycopy(melWindow[r + MEL_FRAMES_PER_CHUNK], 0, melWindow[r], 0, NUM_MELS)
        }
        for (f in 0 until MEL_FRAMES_PER_CHUNK) {
            val dst = melWindow[EMB_FRAMES - MEL_FRAMES_PER_CHUNK + f]
            val src = melOut[0][0][f]
            for (m in 0 until NUM_MELS) dst[m] = src[m] / 10f + 2f
        }
        melFrames += MEL_FRAMES_PER_CHUNK
        if (melFrames < EMB_FRAMES) return false  // still warming up (~0.7 s)

        // One embedding per chunk over the trailing 76-frame window.
        for (r in 0 until EMB_FRAMES) {
            for (m in 0 until NUM_MELS) embIn[0][r][m][0] = melWindow[r][m]
        }
        embedding.run(embIn, embOut)
        for (r in 0 until WW_EMBEDDINGS - 1) {
            System.arraycopy(embWindow[r + 1], 0, embWindow[r], 0, EMB_DIM)
        }
        System.arraycopy(embOut[0][0][0], 0, embWindow[WW_EMBEDDINGS - 1], 0, EMB_DIM)
        embCount++
        if (embCount < WW_EMBEDDINGS) return false

        for (r in 0 until WW_EMBEDDINGS) {
            System.arraycopy(embWindow[r], 0, wakeIn[0][r], 0, EMB_DIM)
        }
        wake.run(wakeIn, wakeOut)
        if (wakeOut[0][0] >= threshold) {
            reset()
            deafFor = REFRACTORY_CHUNKS
            return true
        }
        return false
    }

    /** Forget everything heard so far (post-detection, or after the mic was away). */
    fun reset() {
        audioWindow.fill(0f)
        melWindow.forEach { it.fill(0f) }
        embWindow.forEach { it.fill(0f) }
        melFrames = 0
        embCount = 0
    }

    fun close() {
        melspec.close()
        embedding.close()
        wake.close()
    }

    private fun interpreter(context: Context, asset: String): Interpreter {
        // Name the failing stage — a bare TFLite/UnsatisfiedLinkError message alone doesn't
        // say which of the three models (or the native runtime) is the problem.
        try {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            return Interpreter(buf, Interpreter.Options().setNumThreads(1))
        } catch (t: Throwable) {
            throw IllegalStateException("$asset: ${t.message ?: t.javaClass.simpleName}", t)
        }
    }
}

package com.example.matrixclock.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/** The spoken commands the app understands. Every one is prefixed with the "matrix" keyword. */
enum class VoiceCommand(val phrase: String) {
    TIME("matrix time"),
    CODE("matrix code"),
    DATE("matrix date"),
    WEATHER("matrix weather")
}

/**
 * Offline wake-word spotting with Vosk.
 *
 * The recogniser is constrained to a tiny phrase grammar, which makes it both far more accurate
 * than open-vocabulary transcription and cheap enough to run continuously. Nothing is recorded or
 * sent anywhere — audio is consumed frame by frame and discarded.
 *
 * Requiring the "matrix" prefix on every command is what keeps this usable in a room where people
 * are talking: a bare "time" or "code" in conversation no longer triggers anything.
 */
class VoiceCommands(
    private val context: Context,
    private val onCommand: (VoiceCommand) -> Unit
) {

    private companion object {
        const val TAG = "VoiceCommands"
        const val ASSET_DIR = "model-en-us"
        const val SAMPLE_RATE = 16000f

        /** "[unk]" lets the recogniser say "none of these" instead of forcing a wrong match. */
        val GRAMMAR: String = VoiceCommand.entries.joinToString(
            prefix = "[", postfix = ", \"[unk]\"]", separator = ", "
        ) { "\"${it.phrase}\"" }

        /** Per-word confidence a final result must clear before it counts as a command. */
        const val MIN_CONFIDENCE = 0.85

        /** One spoken command should not fire repeatedly from the tail of the same utterance. */
        const val REARM_MS = 2500L
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    @Volatile
    var isReady = false
        private set

    private var mutedUntilMs = 0L

    /** Unpacks the bundled model if needed and builds the recogniser. Call off the main thread. */
    fun prepare(): Boolean {
        if (isReady) return true
        // Catch Throwable, not Exception: JNA reports a missing or unloadable libvosk.so as
        // UnsatisfiedLinkError, which is an Error and would otherwise sail past and kill the thread.
        return try {
            val started = System.currentTimeMillis()
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val dir = unpackModel()
            Log.i(TAG, "Model at ${dir.absolutePath}, unpacked in ${System.currentTimeMillis() - started} ms")
            val loaded = Model(dir.absolutePath)
            model = loaded
            recognizer = Recognizer(loaded, SAMPLE_RATE, GRAMMAR).apply {
                // Per-word confidences are what let us reject a noisy near-match.
                setWords(true)
            }
            isReady = true
            Log.i(TAG, "Vosk ready in ${System.currentTimeMillis() - started} ms, grammar=$GRAMMAR")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Vosk unavailable, voice commands disabled", t)
            release()
            false
        }
    }

    /** Feeds PCM samples; call from the audio thread. */
    fun process(samples: ShortArray, length: Int, nowMs: Long) {
        val rec = recognizer ?: return
        try {
            // Final results only. Partial results are speculative hypotheses that get revised, and
            // acting on them made room noise fire commands; a final result arrives once Vosk has
            // seen the end of an utterance, at the cost of about a second of latency.
            if (!rec.acceptWaveForm(samples, length)) return

            val json = JSONObject(rec.result)
            val text = json.optString("text").orEmpty().trim()
            if (text.isEmpty() || nowMs < mutedUntilMs) return

            // A constrained grammar will always snap audio onto its nearest phrase, so the text
            // matching is only half the test: the whole utterance must be the phrase and nothing
            // else, and every word in it has to be recognised confidently.
            val command = VoiceCommand.entries.firstOrNull { it.phrase == text } ?: return
            val confidence = minimumConfidence(json)
            if (confidence < MIN_CONFIDENCE) {
                Log.i(TAG, "Ignoring '$text', confidence %.2f below %.2f".format(confidence, MIN_CONFIDENCE))
                return
            }

            Log.i(TAG, "Heard '${command.phrase}' (confidence %.2f)".format(confidence))
            mutedUntilMs = nowMs + REARM_MS
            rec.reset()
            onCommand(command)
        } catch (e: Exception) {
            Log.w(TAG, "Recognition step failed", e)
        }
    }

    /** Lowest per-word confidence in a final result; 0 when the recogniser reported no words. */
    private fun minimumConfidence(result: JSONObject): Double {
        val words = result.optJSONArray("result") ?: return 0.0
        if (words.length() == 0) return 0.0
        var lowest = 1.0
        for (i in 0 until words.length()) {
            lowest = minOf(lowest, words.optJSONObject(i)?.optDouble("conf", 0.0) ?: 0.0)
        }
        return lowest
    }

    fun release() {
        isReady = false
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        recognizer = null
        model = null
    }

    /**
     * Copies the model out of assets into internal storage, where Vosk can mmap it.
     * A marker file keyed to the app version means this only happens once per install.
     */
    private fun unpackModel(): File {
        val target = File(context.filesDir, ASSET_DIR)
        val marker = File(target, ".unpacked-v${versionCode()}")
        if (marker.exists()) return target

        if (target.exists()) target.deleteRecursively()
        copyAssetDir(ASSET_DIR, target)
        marker.createNewFile()
        return target
    }

    private fun versionCode(): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val assets = context.assets
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetDir("$assetPath/$child", File(target, child))
    }
}

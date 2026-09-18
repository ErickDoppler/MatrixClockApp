package com.example.matrixclock.voice

import android.content.Context
import android.provider.Settings as AndroidSettings
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.matrixclock.settings.Settings
import java.util.Calendar
import java.util.Locale

/**
 * Speaks the time through whichever TTS engine the user selected.
 *
 * This deliberately uses the platform [TextToSpeech] API rather than embedding a synthesiser, so
 * any installed engine works — including the bundled open-source sherpa-onnx one, which is offline.
 */
class Speaker(private val context: Context, private val settings: Settings) {

    private companion object {
        const val TAG = "Speaker"
        const val UTTERANCE_ID = "matrix-clock-time"
    }

    private var tts: TextToSpeech? = null

    @Volatile
    var isReady = false
        private set

    /** True once an engine has initialised; false means nothing on this device can speak. */
    fun start(onReady: (Boolean) -> Unit = {}) {
        if (tts != null) return
        val engine = resolveEngine()
        val startedAt = SystemClock.elapsedRealtime()
        val listener = TextToSpeech.OnInitListener { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) {
                Log.i(TAG, "TTS ready in ${SystemClock.elapsedRealtime() - startedAt} ms")
                applyVoiceSettings()
                tts?.setOnUtteranceProgressListener(timingListener)
            } else {
                Log.w(TAG, "TTS init failed (status=$status, engine=${engine ?: "system default"})")
            }
            onReady(isReady)
        }
        Log.i(TAG, "Starting TTS with engine=${engine ?: "system default"}")
        tts = if (engine == null) {
            TextToSpeech(context, listener)
        } else {
            TextToSpeech(context, listener, engine)
        }
    }

    /**
     * Logs how long each announcement takes to begin and to finish.
     *
     * The gap before audio starts is the number that matters on old hardware: a neural engine can
     * sound far better than eSpeak and still be unusable if the device takes seconds to synthesise.
     */
    private val timingListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.i(TAG, "Audio started after ${SystemClock.elapsedRealtime() - requestedAt} ms")
        }

        override fun onDone(utteranceId: String?) {
            Log.i(TAG, "Audio finished after ${SystemClock.elapsedRealtime() - requestedAt} ms")
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "Utterance failed after ${SystemClock.elapsedRealtime() - requestedAt} ms")
        }
    }

    @Volatile
    private var requestedAt = 0L

    /**
     * Which engine package to hand to [TextToSpeech], or null to let it use the system default.
     *
     * A device that has never had a speech engine leaves `tts_default_synth` unset, and in that
     * state the no-engine constructor fails to initialise even when an engine is installed — so an
     * engine has to be named explicitly. This is the normal case on a phone that shipped without
     * Google TTS and then had one sideloaded.
     */
    private fun resolveEngine(): String? {
        settings.ttsEngine?.takeIf { it.isNotEmpty() }?.let { return it }

        val installed = TtsEngineInstaller.installedEngines(context)
        if (installed.isEmpty()) return null

        val systemDefault = runCatching {
            AndroidSettings.Secure.getString(context.contentResolver, "tts_default_synth")
        }.getOrNull()

        // A valid system default is the user's own choice, so leave it alone.
        if (!systemDefault.isNullOrEmpty() && installed.contains(systemDefault)) return null

        Log.i(TAG, "No usable system TTS default; falling back to ${installed.first()}")
        return installed.first()
    }

    /** Re-applies voice and rate after the user changes them in settings. */
    fun applyVoiceSettings() {
        val engine = tts ?: return
        engine.setSpeechRate(settings.ttsRate)
        val wanted = settings.ttsVoice
        if (!wanted.isNullOrEmpty()) {
            val match = runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == wanted }
            if (match != null) {
                engine.voice = match
                return
            }
        }
        runCatching { engine.language = Locale.getDefault() }
    }

    /** Rebuilds against a different engine package; the old one is shut down first. */
    fun restart(onReady: (Boolean) -> Unit = {}) {
        shutdown()
        start(onReady)
    }

    fun availableVoices(): List<Voice> =
        runCatching { tts?.voices?.sortedBy { it.name } }.getOrNull().orEmpty()

    fun speakTime(now: Long = System.currentTimeMillis()) = speak(spokenTime(now))

    /** Speaks arbitrary text, replacing anything already queued. */
    fun speak(text: String) {
        val engine = tts ?: return
        if (!isReady) {
            Log.w(TAG, "Asked to speak before an engine was ready: $text")
            return
        }
        Log.i(TAG, "Speaking: $text")
        requestedAt = SystemClock.elapsedRealtime()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        isReady = false
    }

    /** "The time is nine forty three" reads better than a bare "09:43". */
    private fun spokenTime(now: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val minutePart = when {
            minute == 0 -> "o'clock"
            minute < 10 -> "oh $minute"
            else -> minute.toString()
        }
        return "The time is $hour $minutePart"
    }
}

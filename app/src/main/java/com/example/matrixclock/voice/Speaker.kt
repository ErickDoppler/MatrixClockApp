package com.example.matrixclock.voice

import android.content.Context
import android.speech.tts.TextToSpeech
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
        val engine = settings.ttsEngine
        val listener = TextToSpeech.OnInitListener { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) {
                applyVoiceSettings()
            } else {
                Log.w(TAG, "TTS init failed (status=$status)")
            }
            onReady(isReady)
        }
        tts = if (engine.isNullOrEmpty()) {
            TextToSpeech(context, listener)
        } else {
            TextToSpeech(context, listener, engine)
        }
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

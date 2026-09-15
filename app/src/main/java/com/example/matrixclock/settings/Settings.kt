package com.example.matrixclock.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Every user-tunable value, backed by [SharedPreferences].
 *
 * Values are held as plain properties so the render loop never touches disk; writes go through the
 * setters and notify [listener] so a running [com.example.matrixclock.MatrixView] can re-tune itself.
 */
class Settings(context: Context) {

    companion object {
        private const val FILE = "matrix_clock_settings"

        private const val KEY_GLYPH_SIZE = "glyph_size_dp"
        private const val KEY_GLYPH_DENSITY = "glyph_density"
        private const val KEY_GLYPH_SPEED = "glyph_speed"
        private const val KEY_CLOCK_SIZE = "clock_size"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_MOTION_ENABLED = "motion_enabled"

        const val GLYPH_SIZE_MIN = 8f
        const val GLYPH_SIZE_MAX = 40f
        const val GLYPH_SIZE_DEFAULT = 16f

        /** Fraction of available columns that actually carry a falling stream. */
        const val DENSITY_MIN = 0.2f
        const val DENSITY_MAX = 1f
        const val DENSITY_DEFAULT = 1f

        /** Clock face height as a fraction of screen width. */
        const val CLOCK_SIZE_MIN = 0.04f
        const val CLOCK_SIZE_MAX = 0.25f
        const val CLOCK_SIZE_DEFAULT = 0.1f

        const val SPEED_MIN = 0.2f
        const val SPEED_MAX = 3f
        const val SPEED_DEFAULT = 1f

        const val TTS_RATE_MIN = 0.5f
        const val TTS_RATE_MAX = 2f
        const val TTS_RATE_DEFAULT = 1f
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Invoked after any value changes, on the thread that performed the write. */
    var listener: (() -> Unit)? = null

    var glyphSizeDp: Float
        get() = prefs.getFloat(KEY_GLYPH_SIZE, GLYPH_SIZE_DEFAULT)
        set(value) = put { putFloat(KEY_GLYPH_SIZE, value.coerceIn(GLYPH_SIZE_MIN, GLYPH_SIZE_MAX)) }

    var glyphDensity: Float
        get() = prefs.getFloat(KEY_GLYPH_DENSITY, DENSITY_DEFAULT)
        set(value) = put { putFloat(KEY_GLYPH_DENSITY, value.coerceIn(DENSITY_MIN, DENSITY_MAX)) }

    var glyphSpeed: Float
        get() = prefs.getFloat(KEY_GLYPH_SPEED, SPEED_DEFAULT)
        set(value) = put { putFloat(KEY_GLYPH_SPEED, value.coerceIn(SPEED_MIN, SPEED_MAX)) }

    var clockSize: Float
        get() = prefs.getFloat(KEY_CLOCK_SIZE, CLOCK_SIZE_DEFAULT)
        set(value) = put { putFloat(KEY_CLOCK_SIZE, value.coerceIn(CLOCK_SIZE_MIN, CLOCK_SIZE_MAX)) }

    /** TTS engine package name, or null to use the system default. */
    var ttsEngine: String?
        get() = prefs.getString(KEY_TTS_ENGINE, null)
        set(value) = put { putString(KEY_TTS_ENGINE, value) }

    /** Voice name within the selected engine, or null for that engine's default. */
    var ttsVoice: String?
        get() = prefs.getString(KEY_TTS_VOICE, null)
        set(value) = put { putString(KEY_TTS_VOICE, value) }

    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, TTS_RATE_DEFAULT)
        set(value) = put { putFloat(KEY_TTS_RATE, value.coerceIn(TTS_RATE_MIN, TTS_RATE_MAX)) }

    var motionEnabled: Boolean
        get() = prefs.getBoolean(KEY_MOTION_ENABLED, true)
        set(value) = put { putBoolean(KEY_MOTION_ENABLED, value) }

    private inline fun put(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        listener?.invoke()
    }
}

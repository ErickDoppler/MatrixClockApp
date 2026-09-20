package com.example.matrixclock.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Every user-tunable value, backed by [SharedPreferences].
 *
 * Values are held as plain properties so the render loop never touches disk; writes go through the
 * setters and notify [listener] so a running [com.example.matrixclock.MatrixView] can re-tune itself.
 */
/** A place picked by hand in settings. */
data class City(val name: String, val latitude: Double, val longitude: Double)

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
        private const val KEY_CITY_NAME = "city_name"
        private const val KEY_CITY_LAT = "city_latitude"
        private const val KEY_CITY_LON = "city_longitude"

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

    /**
     * A city chosen by hand, or null to use the device's own location.
     *
     * Stored because plenty of devices never get a location fix — a WiFi-only tablet on a shelf, or
     * one with location switched off — and without coordinates there is no sunrise and no forecast.
     */
    val city: City?
        get() {
            val name = prefs.getString(KEY_CITY_NAME, null)
            if (name.isNullOrEmpty()) return null
            if (!prefs.contains(KEY_CITY_LAT) || !prefs.contains(KEY_CITY_LON)) return null
            return City(name, prefs.getFloat(KEY_CITY_LAT, 0f).toDouble(), prefs.getFloat(KEY_CITY_LON, 0f).toDouble())
        }

    fun setCity(name: String, latitude: Double, longitude: Double) = put {
        putString(KEY_CITY_NAME, name)
        // Float is good to about a metre at these magnitudes, far finer than a forecast grid.
        putFloat(KEY_CITY_LAT, latitude.toFloat())
        putFloat(KEY_CITY_LON, longitude.toFloat())
    }

    /** Falls back to the device location again. */
    fun clearCity() = put {
        remove(KEY_CITY_NAME)
        remove(KEY_CITY_LAT)
        remove(KEY_CITY_LON)
    }

    private inline fun put(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        listener?.invoke()
    }
}

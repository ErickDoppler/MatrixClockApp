package com.example.matrixclock.ambient

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.matrixclock.settings.Settings
import com.example.matrixclock.voice.Speaker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Builds and speaks the date and weather announcements.
 *
 * Everything that can be computed offline is: the date and the sunrise/sunset times need only a
 * position, never a network round trip. Only the weather itself goes online, and only when asked.
 *
 * The position is whichever city was chosen in settings, falling back to the device location. Many
 * devices never produce a fix at all, so being able to name a city is what makes these work there.
 */
class Announcer(
    private val context: Context,
    private val speaker: Speaker,
    private val settings: Settings
) {

    private companion object {
        const val TAG = "Announcer"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val sensors = AmbientSensors(context)

    /** "Today is Monday, 15 September. Sunrise at 6:42, sunset at 19:05." */
    fun announceDate() {
        val now = System.currentTimeMillis()
        // The sentence around it is English, so the date has to be too: on a device set to another
        // locale, Locale.getDefault() produced "Today is вівторок, 15 вересня".
        val dateText = SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(Date(now))
        val here = where()

        if (here == null) {
            speaker.speak("Today is $dateText. ${noLocationReason()}")
            return
        }

        val sun = SunTimes.calculate(here.latitude, here.longitude, now)
        val sunrise = sun.sunriseMs?.let(::spokenClock)
        val sunset = sun.sunsetMs?.let(::spokenClock)

        val text = when {
            sunrise != null && sunset != null ->
                "Today is $dateText. Sunrise at $sunrise, sunset at $sunset."
            // Inside the polar circles the sun may not cross the horizon at all.
            else -> "Today is $dateText. The sun does not rise or set here today."
        }
        speaker.speak(text)
    }

    /** Current conditions from the network, plus whatever this device can measure itself. */
    fun announceWeather() {
        val here = where()
        // Read the onboard sensors regardless: they work with no network and no location.
        sensors.read { reading ->
            if (here == null) {
                val onboard = describeOnboard(reading)
                val why = noLocationReason()
                speaker.speak(if (onboard.isEmpty()) why else "$why $onboard")
                return@read
            }
            executor.execute {
                val conditions = WeatherService.fetch(here.latitude, here.longitude)
                val text = composeWeather(conditions, reading)
                main.post { speaker.speak(text) }
            }
        }
    }

    private fun composeWeather(
        conditions: WeatherService.Conditions?,
        reading: AmbientSensors.Reading
    ): String {
        val onboard = describeOnboard(reading)
        if (conditions == null) {
            return if (onboard.isEmpty()) "I could not reach the weather service."
            else "I could not reach the weather service. $onboard"
        }

        val parts = mutableListOf<String>()
        parts += "Currently ${conditions.temperatureC.roundToInt()} degrees, ${conditions.description}"
        conditions.apparentC?.let { parts += "feels like ${it.roundToInt()}" }
        conditions.windKph?.let { parts += "wind ${it.roundToInt()} kilometres per hour" }
        // Prefer the onboard humidity sensor when there is one; otherwise use the forecast value.
        if (reading.humidityPercent == null) {
            conditions.humidityPercent?.let { parts += "humidity $it percent" }
        }

        val forecast = parts.joinToString(", ") + "."
        return if (onboard.isEmpty()) forecast else "$forecast $onboard"
    }

    /** Only mentions sensors this device actually has. */
    private fun describeOnboard(reading: AmbientSensors.Reading): String {
        if (!reading.hasAny) return ""
        val parts = mutableListOf<String>()
        reading.temperatureC?.let { parts += "temperature ${it.roundToInt()} degrees" }
        reading.humidityPercent?.let { parts += "humidity ${it.roundToInt()} percent" }
        reading.pressureHpa?.let { parts += "pressure ${it.roundToInt()} hectopascals" }
        return "On board sensors read " + parts.joinToString(", ") + "."
    }

    /** "6:42" rather than "06:42", which engines tend to read out digit by digit. */
    private fun spokenClock(atMs: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = atMs }
        val minute = calendar.get(Calendar.MINUTE)
        return "${calendar.get(Calendar.HOUR_OF_DAY)}:${"%02d".format(minute)}"
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Where to report for: the city chosen in settings if there is one, otherwise the device's own
     * position.
     *
     * A hand-picked city wins outright, and needs neither the permission nor a fix — which is the
     * whole point, since a WiFi-only tablet may never produce one.
     */
    private fun where(): Coordinates? {
        settings.city?.let { return Coordinates(it.latitude, it.longitude) }
        val fix = lastKnownLocation() ?: return null
        return Coordinates(fix.latitude, fix.longitude)
    }

    private data class Coordinates(val latitude: Double, val longitude: Double)

    /** Explains a missing position in terms of what the user can actually do about it. */
    private fun noLocationReason(): String = when {
        !hasLocationPermission() ->
            "I have no city set and no location access. Pick a city in settings."
        else ->
            "I have no city set and no location fix yet. Pick a city in settings."
    }

    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return try {
            // Newest fix across the providers this device offers; no active GPS request is made.
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location unavailable", e)
            null
        }
    }

    fun shutdown() = executor.shutdown()
}

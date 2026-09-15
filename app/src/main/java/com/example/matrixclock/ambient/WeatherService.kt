package com.example.matrixclock.ambient

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Current conditions from Open-Meteo.
 *
 * Open-Meteo needs no API key and no account, which keeps the app free of credentials — it is the
 * only part of Matrix Clock that touches the network, and only when you ask for the weather.
 */
object WeatherService {

    private const val TAG = "WeatherService"
    private const val TIMEOUT_MS = 8000

    data class Conditions(
        val temperatureC: Double,
        val apparentC: Double?,
        val humidityPercent: Int?,
        val windKph: Double?,
        val description: String
    )

    /** Blocking network call — must not run on the main thread. Null when unreachable. */
    fun fetch(latitude: Double, longitude: Double): Conditions? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"

        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            val body = connection.use { it.inputStream.bufferedReader().readText() }
            val current = JSONObject(body).getJSONObject("current")

            Conditions(
                temperatureC = current.getDouble("temperature_2m"),
                apparentC = current.optDouble("apparent_temperature").takeIf { !it.isNaN() },
                humidityPercent = current.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
                windKph = current.optDouble("wind_speed_10m").takeIf { !it.isNaN() },
                description = describe(current.optInt("weather_code", -1))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Weather lookup failed", e)
            null
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    /** WMO weather interpretation codes, grouped into phrases that read well aloud. */
    private fun describe(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80, 81 -> "rain showers"
        82 -> "violent rain showers"
        85, 86 -> "snow showers"
        95 -> "a thunderstorm"
        96, 99 -> "a thunderstorm with hail"
        else -> "unknown conditions"
    }
}

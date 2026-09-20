package com.example.matrixclock.ambient

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Turns a typed city name into coordinates, using Open-Meteo's geocoding service.
 *
 * Same provider as the forecast and equally key-free. Only used when the user is choosing a city in
 * settings; once chosen, the coordinates are stored and nothing here runs again.
 */
object Geocoder {

    private const val TAG = "Geocoder"
    private const val TIMEOUT_MS = 8000
    private const val MAX_RESULTS = 8

    /** One candidate city. [label] is what the user picks from. */
    data class Place(
        val name: String,
        val region: String,
        val country: String,
        val latitude: Double,
        val longitude: Double
    ) {
        /** "Springfield, Missouri, United States" — enough to tell duplicates apart. */
        val label: String
            get() = listOf(name, region, country).filter { it.isNotBlank() }.joinToString(", ")
    }

    /** Blocking network call — must not run on the main thread. Empty list when nothing matched. */
    fun search(query: String): List<Place> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${URLEncoder.encode(trimmed, "UTF-8")}" +
            "&count=$MAX_RESULTS&language=en&format=json"

        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            val body = try {
                connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }

            // A query with no matches omits "results" entirely rather than returning an empty list.
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    add(
                        Place(
                            name = item.optString("name"),
                            region = item.optString("admin1"),
                            country = item.optString("country"),
                            latitude = item.optDouble("latitude"),
                            longitude = item.optDouble("longitude")
                        )
                    )
                }
            }.filter { it.name.isNotBlank() && !it.latitude.isNaN() && !it.longitude.isNaN() }
        } catch (e: Exception) {
            Log.w(TAG, "City lookup failed for '$trimmed'", e)
            emptyList()
        }
    }
}

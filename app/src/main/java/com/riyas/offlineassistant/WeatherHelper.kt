package com.riyas.SpideyAssistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

object WeatherHelper {

    private val http = OkHttpClient()

    /**
     * Fetches a weather phrase. If [locationName] is given (e.g. "Chennai",
     * "Kodaikanal"), it's geocoded and used instead of the device's GPS fix.
     */
    suspend fun fetchWeatherPhrase(context: Context, locationName: String? = null): String =
        withContext(Dispatchers.IO) {

            var lat: Double
            var lon: Double
            var displayName: String? = null

            if (!locationName.isNullOrBlank()) {
                // Named-place path: geocode it, no GPS/location permission needed.
                val geocoded = try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(locationName, 1)?.firstOrNull()
                } catch (e: Exception) {
                    null
                }

                if (geocoded == null) {
                    return@withContext "I couldn't find a place called \"$locationName\"."
                }

                lat = geocoded.latitude
                lon = geocoded.longitude
                displayName = geocoded.locality ?: locationName
            } else {
                // No city named — fall back to the device's current location.
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPermission) {
                    return@withContext "I need location permission to check the weather — enable it in settings and try again."
                }

                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var location: android.location.Location? = null
                for (provider in lm.getProviders(true)) {
                    location = lm.getLastKnownLocation(provider)
                    if (location != null) break
                }

                if (location == null) {
                    return@withContext "I couldn't get your location right now — try again in a moment, or make sure location is turned on."
                }

                lat = location.latitude
                lon = location.longitude
            }

            try {
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon&current_weather=true"
                val response = http.newCall(Request.Builder().url(url).build()).execute()
                val body = response.body?.string()
                    ?: return@withContext "Couldn't reach the weather service just now."

                val current = JSONObject(body).getJSONObject("current_weather")
                val temp = current.getDouble("temperature")
                val code = current.getInt("weathercode")

                val place = if (displayName != null) " in $displayName" else ""
                "Looks like ${weatherCodeToText(code)}$place, around ${temp.toInt()}°C right now."
            } catch (e: Exception) {
                "Something went wrong checking the weather: ${e.message}"
            }
        }

    private fun weatherCodeToText(code: Int): String = when (code) {
        0 -> "clear skies"
        1, 2, 3 -> "partly cloudy"
        45, 48 -> "foggy"
        51, 53, 55 -> "light drizzle"
        56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "mixed conditions"
    }
}
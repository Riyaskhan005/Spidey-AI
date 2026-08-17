package com.riyas.SpideyAssistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * WeatherHelper — uses Open-Meteo (https://open-meteo.com), a free weather
 * API that requires no API key or account, combined with the device's last
 * known location (no network geolocation service needed either).
 */
object WeatherHelper {

    private val http = OkHttpClient()

    suspend fun fetchWeatherPhrase(context: Context): String = withContext(Dispatchers.IO) {
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

        try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&current_weather=true"
            val response = http.newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string()
                ?: return@withContext "Couldn't reach the weather service just now."

            val current = JSONObject(body).getJSONObject("current_weather")
            val temp = current.getDouble("temperature")
            val code = current.getInt("weathercode")

            "Looks like ${weatherCodeToText(code)}, around ${temp.toInt()}°C right now."
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

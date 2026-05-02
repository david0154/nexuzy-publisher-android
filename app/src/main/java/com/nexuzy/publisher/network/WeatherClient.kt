package com.nexuzy.publisher.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * WeatherClient
 *
 * Fetches real-time weather for the device's current GPS location using:
 *   - FusedLocationProviderClient  (Google Play Services) for coordinates
 *   - Geocoder                     for reverse-geocoding city/country
 *   - Open-Meteo API               https://open-meteo.com  (100% free, no API key)
 *
 * Requires permissions:
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 *   <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
 *
 * Usage:
 *   val weather = WeatherClient.getWeather(context)
 *   if (weather != null) {
 *       // weather.city, weather.temperature, weather.description, weather.summary()
 *   }
 */
object WeatherClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class WeatherData(
        val city: String,
        val country: String,
        val latitude: Double,
        val longitude: Double,
        val temperature: Double,       // Celsius
        val feelsLike: Double,          // Celsius
        val humidity: Int,              // %
        val windSpeed: Double,          // km/h
        val weatherCode: Int,           // WMO code
        val isDay: Boolean
    ) {
        /** Human-readable weather description from WMO code */
        val description: String get() = wmoDescription(weatherCode)

        /** Short natural-language summary suitable for injecting into AI context */
        fun summary(): String {
            val loc = if (city.isNotBlank()) "$city, $country" else "your location"
            return "Current weather in $loc: ${description.lowercase()}, " +
                "${temperature.toInt()}°C (feels like ${feelsLike.toInt()}°C), " +
                "humidity ${humidity}%, wind ${windSpeed.toInt()} km/h. " +
                "It is currently ${if (isDay) "daytime" else "night-time"}."
        }
    }

    /**
     * Returns [WeatherData] for the device's current location,
     * or null if permission denied / location unavailable / network error.
     */
    suspend fun getWeather(context: Context): WeatherData? {
        val location = getLocation(context) ?: run {
            Log.w("WeatherClient", "Location unavailable")
            return null
        }
        return fetchOpenMeteo(context, location.latitude, location.longitude)
    }

    // ── Location ──────────────────────────────────────────────────────────────────

    private suspend fun getLocation(context: Context): Location? {
        val fine   = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        val hasPerm = ContextCompat.checkSelfPermission(context, fine) == PackageManager.PERMISSION_GRANTED ||
                      ContextCompat.checkSelfPermission(context, coarse) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return null

        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                val cts = CancellationTokenSource()
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        }
    }

    // ── Open-Meteo API ───────────────────────────────────────────────────────────

    private fun fetchOpenMeteo(context: Context, lat: Double, lon: Double): WeatherData? {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat" +
                "&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m" +
                ",wind_speed_10m,weather_code,is_day" +
                "&wind_speed_unit=kmh" +
                "&timezone=auto"

            val req  = Request.Builder().url(url)
                .addHeader("User-Agent", "NexuzyPublisher/2.0")
                .build()
            val res  = http.newCall(req).execute()
            if (!res.isSuccessful) return null

            val body    = res.body?.string() ?: return null
            val root    = JSONObject(body)
            val current = root.getJSONObject("current")

            val temp     = current.getDouble("temperature_2m")
            val feels    = current.getDouble("apparent_temperature")
            val humidity = current.getInt("relative_humidity_2m")
            val wind     = current.getDouble("wind_speed_10m")
            val code     = current.getInt("weather_code")
            val isDay    = current.getInt("is_day") == 1

            // Reverse-geocode city name
            val (city, country) = reverseGeocode(context, lat, lon)

            WeatherData(
                city        = city,
                country     = country,
                latitude    = lat,
                longitude   = lon,
                temperature = temp,
                feelsLike   = feels,
                humidity    = humidity,
                windSpeed   = wind,
                weatherCode = code,
                isDay       = isDay
            )
        } catch (e: Exception) {
            Log.e("WeatherClient", "Open-Meteo fetch failed: ${e.message}")
            null
        }
    }

    private fun reverseGeocode(context: Context, lat: Double, lon: Double): Pair<String, String> {
        return try {
            val gc = Geocoder(context, Locale.ENGLISH)
            @Suppress("DEPRECATION")
            val addresses = gc.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                Pair(
                    addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: "",
                    addr.countryName ?: ""
                )
            } else Pair("", "")
        } catch (e: Exception) {
            Pair("", "")
        }
    }

    // ── WMO Weather Code → description ────────────────────────────────────────────

    private fun wmoDescription(code: Int): String = when (code) {
        0           -> "Clear sky"
        1           -> "Mainly clear"
        2           -> "Partly cloudy"
        3           -> "Overcast"
        45, 48      -> "Foggy"
        51, 53, 55  -> "Drizzle"
        56, 57      -> "Freezing drizzle"
        61, 63, 65  -> "Rain"
        66, 67      -> "Freezing rain"
        71, 73, 75  -> "Snowfall"
        77          -> "Snow grains"
        80, 81, 82  -> "Rain showers"
        85, 86      -> "Snow showers"
        95          -> "Thunderstorm"
        96, 99      -> "Thunderstorm with hail"
        else        -> "Unknown conditions"
    }
}

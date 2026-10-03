package com.vcorr.claudewatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * What the watch can tell Claude, offered as client-side tools. Claude calls one only when a
 * question needs it, and each reads the watch at that moment, so nothing is sent ahead of time.
 */
class WatchTools(private val context: Context) {

    /** A tool failed in a way Claude should hear about and explain, such as a missing permission. */
    class ToolException(message: String) : Exception(message)

    /** The tool definitions sent with every request. */
    val definitions: JSONArray = JSONArray()
        .put(tool("get_location", "The wearer's approximate current location from the watch: coordinates and, when available, the place name."))
        .put(
            tool(
                "get_weather",
                "Current weather and the forecast for the next three days, from Open-Meteo. Without a place, uses the watch's location.",
                JSONObject().put(
                    "place",
                    JSONObject().put("type", "string").put("description", "A town or city, if the wearer named one; leave out for where they are."),
                ),
            )
        )
        .put(tool("get_watch_status", "The watch's battery level and charging state, its connection, and the next alarm."))
        .put(tool("get_heart_rate", "Measures the wearer's heart rate now with the watch's sensor. Takes up to 20 seconds; the wearer should keep still."))
        .put(
            tool(
                "get_calendar",
                "Events on the wearer's calendar, as synced to the watch, from now until the given number of days ahead.",
                JSONObject().put(
                    "days",
                    JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 7).put("description", "How many days ahead; 1 means the rest of today and tonight."),
                ),
            )
        )

    /** A few words for the screen while a tool runs. */
    fun progress(name: String): String = when (name) {
        "get_location" -> "Finding where you are"
        "get_weather" -> "Checking the weather"
        "get_watch_status" -> "Checking the watch"
        "get_heart_rate" -> "Measuring your pulse; keep still"
        "get_calendar" -> "Checking your calendar"
        else -> "Thinking"
    }

    /** Runs a tool and returns its result as text for Claude. */
    suspend fun run(name: String, input: JSONObject): String = when (name) {
        "get_location" -> location()
        "get_weather" -> weather(input.optString("place").takeIf { it.isNotBlank() })
        "get_watch_status" -> status()
        "get_heart_rate" -> heartRate()
        "get_calendar" -> calendar(input.optInt("days", 1).coerceIn(1, 7))
        else -> throw ToolException("Unknown tool $name")
    }

    // ── Location ────────────────────────────────────────────

    private suspend fun location(): String {
        val here = currentLocation()
        val place = placeName(here)
        return "Approximately ${"%.2f".format(Locale.UK, here.latitude)}, ${"%.2f".format(Locale.UK, here.longitude)}" +
            (place?.let { ", in $it" } ?: "") + "."
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            throw ToolException("Location permission is off for ClaudeWatch. The wearer can allow it in the watch's Settings, under Apps.")
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) throw ToolException("Location is turned off on the watch.")

        val fresh = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(providers.first(), signal, context.mainExecutor) { if (cont.isActive) cont.resume(it) }
            }
        }
        return fresh
            ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            ?: throw ToolException("The watch couldn't find its location just now.")
    }

    private suspend fun placeName(at: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            @Suppress("DEPRECATION")
            val address = Geocoder(context, Locale.UK).getFromLocation(at.latitude, at.longitude, 1)?.firstOrNull()
            listOfNotNull(address?.locality ?: address?.subAdminArea, address?.countryName).joinToString(", ").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // ── Weather ─────────────────────────────────────────────

    private suspend fun weather(place: String?): String {
        val (lat, lon, name) = if (place != null) {
            geocode(place)
        } else {
            val here = currentLocation()
            Triple(here.latitude, here.longitude, placeName(here) ?: "the wearer's location")
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${"%.3f".format(Locale.UK, lat)}&longitude=${"%.3f".format(Locale.UK, lon)}" +
            "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,sunrise,sunset" +
            "&wind_speed_unit=ms&timezone=auto&forecast_days=3"
        val json = getJson(url)

        val current = json.getJSONObject("current")
        val now = "Now in $name: ${current.optDouble("temperature_2m").roundToInt()}°C " +
            "(feels like ${current.optDouble("apparent_temperature").roundToInt()}°C), ${describe(current.optInt("weather_code"))}, " +
            "wind ${current.optDouble("wind_speed_10m").roundToInt()} m/s, precipitation ${current.optDouble("precipitation")} mm."

        // Every third hour for the next twelve hours, starting from the current hour.
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val currentHour = current.optString("time").take(13)
        val first = (0 until times.length()).firstOrNull { times.getString(it).take(13) >= currentHour } ?: 0
        val hours = (first until minOf(first + 13, times.length()) step 3).joinToString("; ") { i ->
            "${times.getString(i).substring(11, 16)} ${hourly.getJSONArray("temperature_2m").optDouble(i).roundToInt()}°C, " +
                "${describe(hourly.getJSONArray("weather_code").optInt(i))}, " +
                "${hourly.getJSONArray("precipitation_probability").optInt(i)}% chance of rain"
        }

        val daily = json.getJSONObject("daily")
        val days = (0 until daily.getJSONArray("time").length()).joinToString(" ") { i ->
            val label = when (i) { 0 -> "Today"; 1 -> "Tomorrow"; else -> daily.getJSONArray("time").getString(i) }
            "$label: ${describe(daily.getJSONArray("weather_code").optInt(i))}, " +
                "${daily.getJSONArray("temperature_2m_min").optDouble(i).roundToInt()} to ${daily.getJSONArray("temperature_2m_max").optDouble(i).roundToInt()}°C, " +
                "${daily.getJSONArray("precipitation_sum").optDouble(i)} mm rain (${daily.getJSONArray("precipitation_probability_max").optInt(i)}% chance), " +
                "sunrise ${daily.getJSONArray("sunrise").getString(i).takeLast(5)}, sunset ${daily.getJSONArray("sunset").getString(i).takeLast(5)}."
        }
        return "$now Next hours: $hours. $days Times are local. Source: Open-Meteo."
    }

    private suspend fun geocode(place: String): Triple<Double, Double, String> {
        val json = getJson(
            "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" +
                URLEncoder.encode(place, "UTF-8")
        )
        val hit = json.optJSONArray("results")?.optJSONObject(0) ?: throw ToolException("No place called $place was found.")
        val name = listOfNotNull(hit.optString("name").ifBlank { null }, hit.optString("country").ifBlank { null }).joinToString(", ")
        return Triple(hit.getDouble("latitude"), hit.getDouble("longitude"), name)
    }

    /** WMO weather codes, as Open-Meteo reports them, in words. */
    private fun describe(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
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
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "weather code $code"
    }

    // ── Watch status ────────────────────────────────────────

    private fun status(): String {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (battery.isCharging) "charging" else "not charging"

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val connection = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "the phone, over Bluetooth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "a network"
        }

        val alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
        val alarmText = alarm?.let { "Next alarm: ${SimpleDateFormat("EEEE HH:mm", Locale.UK).format(Date(it))}." } ?: "No alarm set."
        return "Battery $level%, $charging. Connected via $connection. $alarmText"
    }

    // ── Heart rate ──────────────────────────────────────────

    private suspend fun heartRate(): String {
        if (!granted(heartRatePermission())) {
            throw ToolException("Heart rate permission is off for ClaudeWatch. The wearer can allow it in the watch's Settings, under Apps.")
        }
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            ?: throw ToolException("This watch doesn't offer its heart rate sensor to apps.")

        var lastAny: Int? = null
        val reliable = withTimeoutOrNull(HEART_RATE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val bpm = event.values.firstOrNull()?.roundToInt() ?: return
                        if (bpm <= 0) return
                        lastAny = bpm
                        if (event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW && cont.isActive) {
                            sm.unregisterListener(this)
                            cont.resume(bpm)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
        return when {
            reliable != null -> "Heart rate $reliable beats per minute, measured just now."
            lastAny != null -> "Heart rate about $lastAny beats per minute, measured just now, but the sensor marked it unreliable."
            else -> throw ToolException("The watch couldn't read a heart rate; it may not be snug on the wrist.")
        }
    }

    // ── Calendar ────────────────────────────────────────────

    private suspend fun calendar(days: Int): String = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.READ_CALENDAR)) {
            throw ToolException("Calendar permission is off for ClaudeWatch. The wearer can allow it in the watch's Settings, under Apps.")
        }
        val begin = System.currentTimeMillis()
        val end = endOfDay(days)
        // The watch's own calendar store first, then Wear OS's copy of the phone's calendar.
        val events = queryEvents(
            CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, begin)
                ContentUris.appendId(it, end)
            }.build(),
        ).ifEmpty {
            queryEvents(Uri.parse("content://com.google.android.wearable.provider.calendar/instances/when/$begin/$end"))
        }
        if (events.isEmpty()) {
            return@withContext "No events between now and ${SimpleDateFormat("EEEE", Locale.UK).format(Date(end - 1))} night. " +
                "If the wearer expects some, their calendar may not sync to the watch."
        }
        events.take(MAX_EVENTS).joinToString(" ")
    }

    private fun queryEvents(uri: Uri): List<String> = try {
        val day = SimpleDateFormat("EEEE d MMMM", Locale.UK)
        val time = SimpleDateFormat("HH:mm", Locale.UK)
        context.contentResolver.query(
            uri,
            arrayOf("title", "begin", "end", "allDay", "eventLocation"),
            null,
            null,
            "begin ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val title = c.getString(0)?.ifBlank { null } ?: "Untitled event"
                    val start = Date(c.getLong(1))
                    val finish = Date(c.getLong(2))
                    val where = c.getString(4)?.ifBlank { null }?.let { " at $it" } ?: ""
                    add(
                        if (c.getInt(3) == 1) {
                            "${day.format(start)}: $title, all day$where."
                        } else {
                            "${day.format(start)} ${time.format(start)} to ${time.format(finish)}: $title$where."
                        }
                    )
                }
            }
        }.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private fun endOfDay(days: Int): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, days - 1)
        set(java.util.Calendar.HOUR_OF_DAY, 23)
        set(java.util.Calendar.MINUTE, 59)
        set(java.util.Calendar.SECOND, 59)
    }.timeInMillis

    // ── Helpers ─────────────────────────────────────────────

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != 200) throw ToolException("The weather service answered HTTP ${conn.responseCode}.")
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val LOCATION_TIMEOUT_MS = 15_000L
        private const val HEART_RATE_TIMEOUT_MS = 20_000L
        private const val MAX_EVENTS = 15

        /** Android 16 replaced BODY_SENSORS with a heart-rate permission for apps that target it. */
        fun heartRatePermission(): String =
            if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

        /** Everything the tools may need, asked for together with the microphone. */
        fun permissions(): Array<String> = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CALENDAR,
            heartRatePermission(),
        )

        private fun tool(name: String, description: String, properties: JSONObject = JSONObject()) = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("input_schema", JSONObject().put("type", "object").put("properties", properties))
    }
}

package com.vcorr.claudewatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.KeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the watch can tell Claude, offered as client-side tools. Claude calls one only when a
 * question needs it, and each reads the watch at that moment, so nothing is sent ahead of time.
 */
class WatchTools(private val context: Context) {

    private val notes = NoteStore(context)

    /** The app's speaker, so a volume change sets the wearer's own level, not its speaking floor. */
    var speaker: Speaker? = null

    /** Runs on the main thread just before another app's screen may open, e.g. the Clock's. */
    var beforeLeaving: (() -> Unit)? = null

    /** Another app's screen to open once the reply has been spoken, such as Maps' navigation. */
    @Volatile
    var pendingLaunch: Intent? = null

    /** A tool failed in a way Claude should hear about and explain, such as a missing permission. */
    class ToolException(message: String) : Exception(message)

    /** The tool definitions sent with every request. */
    val definitions: JSONArray = JSONArray()
        .put(
            tool(
                "get_claudewatch_help",
                "What ClaudeWatch (this app) can do, with example requests, how it is used, its limits, and which features are switched on for this watch now. " +
                    "Use it when the wearer asks what you can do or how to do something with the watch, or why something doesn't work, rather than guessing.",
            )
        )
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
        .put(tool("get_activity_today", "The wearer's activity so far today as the watch counts it: steps, and where the watch reports them, distance, calories burned and floors climbed, with the time of the latest report."))
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
        .put(
            tool(
                "set_timer",
                "Starts a countdown timer in the watch's Clock app, which rings when it ends.",
                JSONObject()
                    .put("seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 86_400).put("description", "The timer's length in seconds."))
                    .put("label", JSONObject().put("type", "string").put("description", "What it is for, if the wearer said, e.g. \"pasta\".")),
                required = listOf("seconds"),
            )
        )
        .put(
            tool(
                "set_alarm",
                "Sets an alarm in the watch's Clock app, for the next time the clock shows that hour and minute.",
                JSONObject()
                    .put("hour", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 23).put("description", "Hour, 24-hour clock, watch's local time."))
                    .put("minute", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 59))
                    .put("label", JSONObject().put("type", "string").put("description", "What it is for, if the wearer said.")),
                required = listOf("hour", "minute"),
            )
        )
        .put(
            tool(
                "control_media",
                "Controls whatever is playing on the watch or through it (music, podcasts), as the media buttons would, and the media volume.",
                JSONObject()
                    .put(
                        "action",
                        JSONObject().put("type", "string")
                            .put("enum", JSONArray(listOf("play", "pause", "next", "previous", "volume_up", "volume_down", "set_volume"))),
                    )
                    .put("level", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100).put("description", "For set_volume: the media volume in percent.")),
                required = listOf("action"),
            )
        )
        .put(tool("get_notifications", "The notifications on the watch now, newest first: app, time, title and text, and the last few messages of a conversation. This is how to see the wearer's messages from any app (Signal, WhatsApp, texts, email) and missed calls. Use only when the wearer asks about their notifications or messages, and always check rather than guessing that there are none."))
        .put(
            tool(
                "prepare_reply",
                "Gets a reply to a message ready, without sending it, through the notification's own reply action (as typing a reply on the watch would), e.g. to a Signal or WhatsApp message. " +
                    "Only for notifications get_notifications marked \"can reply\", by their number in its listing from this turn. " +
                    "Returns the words and the recipient to read back; send with send_prepared_reply only once the wearer confirms.",
                JSONObject()
                    .put("number", JSONObject().put("type", "integer").put("minimum", 1).put("description", "The notification's number in this turn's get_notifications listing."))
                    .put("text", JSONObject().put("type", "string").put("description", "The reply, in the wearer's words.")),
                required = listOf("number", "text"),
            )
        )
        .put(
            tool(
                "send_prepared_reply",
                "Sends the reply prepare_reply got ready, exactly as read back, to the recipient it named. Only after the wearer has confirmed it; it is kept for three minutes.",
            )
        )
        .put(
            tool(
                "set_do_not_disturb",
                "Turns the watch's Do Not Disturb on (only alarms and priority interruptions get through) or off, optionally until a time.",
                JSONObject()
                    .put("on", JSONObject().put("type", "boolean"))
                    .put("until", JSONObject().put("type", "string").put("description", "When turning on: the local time to turn it off again, HH:MM, the next time the clock shows it.")),
                required = listOf("on"),
            )
        )
        .put(tool("get_now_playing", "What is playing on the watch or through it now: the app, title, artist and whether it is playing or paused."))
        .put(tool("get_electricity_prices", "Finland's electricity spot prices from now on, in cents per kWh including VAT, as hourly averages with the cheapest and dearest hours, from porssisahko.net. Tomorrow's prices appear in the afternoon."))
        .put(
            tool(
                "add_reminder",
                "Sets a reminder: at the time, the watch buzzes and shows the text as a notification. Give either at or in_minutes.",
                JSONObject()
                    .put("text", JSONObject().put("type", "string").put("description", "What to remind the wearer of, briefly, in their language."))
                    .put("at", JSONObject().put("type", "string").put("description", "Local date and time, YYYY-MM-DDTHH:MM."))
                    .put("in_minutes", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 525_600)),
                required = listOf("text"),
            )
        )
        .put(tool("list_reminders", "The reminders still to come, soonest first, with their ids and times."))
        .put(
            tool(
                "cancel_reminder",
                "Cancels a reminder by the id list_reminders or add_reminder gave.",
                JSONObject().put("id", JSONObject().put("type", "integer")),
                required = listOf("id"),
            )
        )
        .put(
            tool(
                "get_directions",
                "How far and in which compass direction a place is from the wearer, as the crow flies (not by road). " +
                    "Always use this rather than estimating distances or directions yourself. For which way to turn, also call get_compass and compare the bearings.",
                JSONObject()
                    .put("destination", JSONObject().put("type", "string").put("description", "A town, address or named place, e.g. \"Tampere\" or \"Hämeenkatu 10, Tampere\"."))
                    .put("note_id", JSONObject().put("type", "integer").put("description", "Instead of a destination: a saved note's id, for the place saved with it.")),
            )
        )
        .put(
            tool(
                "start_navigation",
                "Opens turn-by-turn navigation in Google Maps on the watch, from where the wearer is, once your reply has been spoken; the watch then shows Maps. Only when the wearer asks to be guided or navigated somewhere.",
                JSONObject()
                    .put("destination", JSONObject().put("type", "string").put("description", "A town, address or named place."))
                    .put("note_id", JSONObject().put("type", "integer").put("description", "Instead of a destination: a saved note's id, to navigate to the place saved with it."))
                    .put("mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("walk", "bicycle", "drive"))).put("description", "Walking if the wearer didn't say.")),
            )
        )
        .put(tool("get_air_pressure", "Air pressure from the watch's barometer, with the sea-level pressure here now, three hours ago and three hours ahead (a falling trend often means worsening weather), and the watch's altitude estimated from the two."))
        .put(tool("get_compass", "Which way the watch's 12 o'clock edge points, as a compass bearing, read while the wearer holds the watch flat. Useful with recall's directions to a saved place."))
        .put(
            tool(
                "remember",
                "Saves a note on the watch that the wearer asked you to remember, such as where they parked, optionally with their current location.",
                JSONObject()
                    .put("text", JSONObject().put("type", "string").put("description", "The note, in the wearer's words, briefly."))
                    .put("at_current_location", JSONObject().put("type", "boolean").put("description", "True to save where the wearer is now too, e.g. for a parked car.")),
                required = listOf("text"),
            )
        )
        .put(
            tool(
                "recall",
                "The notes saved with remember, newest first, each with its id, when it was saved, and where, if a place was saved.",
                JSONObject().put(
                    "with_directions",
                    JSONObject().put("type", "boolean").put("description", "True when the wearer wants to know how far, or which way, a saved place is from here."),
                ),
            )
        )
        .put(
            tool(
                "forget",
                "Deletes saved notes, by the ids recall gives, or all of them.",
                JSONObject()
                    .put("ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")))
                    .put("all", JSONObject().put("type", "boolean").put("description", "True to delete every note; only when the wearer clearly asks.")),
            )
        )

    /** A few words for the screen while a tool runs. */
    fun progress(name: String): String? = when (name) {
        "get_claudewatch_help" -> "Looking up what I can do"
        "get_location" -> "Finding where you are"
        "get_weather" -> "Checking the weather"
        "get_watch_status" -> "Checking the watch"
        "get_heart_rate" -> "Measuring your pulse; keep still"
        "get_activity_today" -> "Counting your steps"
        "get_calendar" -> "Checking your calendar"
        "set_timer" -> "Setting a timer"
        "set_alarm" -> "Setting an alarm"
        "control_media" -> "Pressing the buttons"
        "get_notifications" -> "Reading your notifications"
        "get_now_playing" -> "Seeing what's playing"
        "prepare_reply" -> "Getting your reply ready"
        "send_prepared_reply" -> "Sending your reply"
        "set_do_not_disturb" -> "Changing Do Not Disturb"
        "get_electricity_prices" -> "Checking electricity prices"
        "add_reminder" -> "Setting a reminder"
        "list_reminders" -> "Looking at your reminders"
        "cancel_reminder" -> "Cancelling a reminder"
        "get_air_pressure" -> "Reading the barometer"
        "get_directions" -> "Working out the way"
        "start_navigation" -> "Opening Maps"
        "get_compass" -> "Hold the watch flat"
        "remember" -> "Making a note"
        "recall" -> "Looking at your notes"
        "forget" -> "Deleting notes"
        else -> null
    }

    /** Runs a tool and returns its result as text for Claude. */
    suspend fun run(name: String, input: JSONObject): String = when (name) {
        "get_claudewatch_help" -> Help.describe(context)
        "get_location" -> location()
        "get_weather" -> weather(input.optString("place").takeIf { it.isNotBlank() })
        "get_watch_status" -> status()
        "get_heart_rate" -> heartRate()
        "get_activity_today" -> activity()
        "get_calendar" -> calendar(input.optInt("days", 1).coerceIn(1, 7))
        "set_timer" -> setTimer(input.optInt("seconds"), input.optString("label").takeIf { it.isNotBlank() })
        "set_alarm" -> setAlarm(input.optInt("hour", -1), input.optInt("minute", -1), input.optString("label").takeIf { it.isNotBlank() })
        "control_media" -> media(input.optString("action"), input.optInt("level", -1))
        "get_notifications" -> NotificationsService.describeActive(context) ?: throw notificationAccessOff()
        "get_now_playing" -> NotificationsService.describeNowPlaying(context) ?: throw notificationAccessOff()
        "prepare_reply" -> prepareReply(input.optInt("number", -1), input.optString("text").trim())
        "send_prepared_reply" -> NotificationsService.sendPreparedReply(context) ?: throw notificationAccessOff()
        "set_do_not_disturb" -> doNotDisturb(input.optBoolean("on"), input.optString("until").takeIf { it.isNotBlank() })
        "get_electricity_prices" -> electricityPrices()
        "add_reminder" -> addReminder(
            input.optString("text").trim(),
            input.optString("at").takeIf { it.isNotBlank() },
            input.optInt("in_minutes", -1).takeIf { it > 0 },
        )
        "list_reminders" -> listReminders()
        "cancel_reminder" -> cancelReminder(input.optInt("id", -1))
        "get_air_pressure" -> airPressure()
        "get_directions" -> directions(input.optString("destination").trim().takeIf { it.isNotEmpty() }, input.optInt("note_id", -1).takeIf { it > 0 })
        "start_navigation" -> navigate(
            input.optString("destination").trim().takeIf { it.isNotEmpty() },
            input.optInt("note_id", -1).takeIf { it > 0 },
            input.optString("mode", "walk"),
        )
        "get_compass" -> compass()
        "remember" -> remember(input.optString("text").trim(), input.optBoolean("at_current_location"))
        "recall" -> recall(input.optBoolean("with_directions"))
        "forget" -> forget(input.optJSONArray("ids"), input.optBoolean("all"))
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
        if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requirePermission(Manifest.permission.ACCESS_COARSE_LOCATION, "Location")
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
        val alarmText = alarm?.let { "Next alarm: ${ukTime("EEEE HH:mm", it)}." } ?: "No alarm set."
        return "Battery $level%, $charging. Connected via $connection. $alarmText"
    }

    // ── Heart rate ──────────────────────────────────────────

    private suspend fun heartRate(): String {
        requirePermission(heartRatePermission(), "Heart rate")
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

    // ── Activity today ──────────────────────────────────────

    private suspend fun activity(): String {
        requirePermission(Manifest.permission.ACTIVITY_RECOGNITION, "Physical activity")
        val prefs = context.getSharedPreferences(StepsService.PREFS, Context.MODE_PRIVATE)
        // Totals arrive in batches, so ask for the latest and give it a moment to land.
        val before = prefs.getLong(StepsService.KEY_AT, 0)
        if (StepsService.flush(context)) {
            withTimeoutOrNull(STEPS_FLUSH_WAIT_MS) {
                while (prefs.getLong(StepsService.KEY_AT, 0) == before) delay(200)
            }
        }
        val at = prefs.getLong(StepsService.KEY_AT, 0)
        if (at == 0L) {
            throw ToolException("No step count has arrived yet. The watch sends steps in batches, so the first may take a while after the app is set up.")
        }
        if (!isToday(at)) {
            return "The watch hasn't reported a step count since midnight (the last report was on " +
                "${ukTime("EEEE", at)}). That doesn't mean no steps today; " +
                "the count arrives in batches, and after a restart only once ClaudeWatch has been opened."
        }
        val steps = prefs.getLong(StepsService.KEY_STEPS, 0)
        // The other totals only when the watch reports them and the report is from today.
        fun today(key: String): Float? =
            prefs.getLong(key + StepsService.AT, 0).takeIf { it != 0L && isToday(it) }?.let { prefs.getFloat(key, 0f) }
        val more = listOfNotNull(
            today(StepsService.KEY_DISTANCE)?.let { "distance ${"%.1f".format(Locale.UK, it / 1000)} km" },
            today(StepsService.KEY_CALORIES)?.let { "${it.roundToInt()} kcal burned (the watch's daily total, which may include resting burn)" },
            today(StepsService.KEY_FLOORS)?.let { "${it.roundToInt()} floors climbed" },
        )
        return "$steps steps today, as of ${ukTime("HH:mm", at)}" + (if (more.isEmpty()) "." else "; ${more.joinToString("; ")}.")
    }

    private fun isToday(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()

    // ── Calendar ────────────────────────────────────────────

    private suspend fun calendar(days: Int): String = withContext(Dispatchers.IO) {
        requirePermission(Manifest.permission.READ_CALENDAR, "Calendar")
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
            return@withContext "No events between now and ${ukTime("EEEE", end - 1)} night. " +
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

    /** The last second of the day [days] - 1 days from today (1 = tonight), in the watch's time zone. */
    private fun endOfDay(days: Int): Long =
        LocalDate.now().plusDays(days - 1L).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ── Timers and alarms ───────────────────────────────────

    private suspend fun setTimer(seconds: Int, label: String?): String {
        if (seconds !in 1..86_400) throw ToolException("A timer must be between one second and 24 hours.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
        openClock(intent)
        return "Asked the watch's Clock app to start a ${duration(seconds)} timer${label?.let { " for $it" } ?: ""}. " +
            "It rings at about ${ukTime("HH:mm", System.currentTimeMillis() + seconds * 1000L)}."
    }

    private suspend fun setAlarm(hour: Int, minute: Int, label: String?): String {
        if (hour !in 0..23 || minute !in 0..59) throw ToolException("That isn't a time on a 24-hour clock.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
        openClock(intent)
        return "Asked the watch's Clock app to set an alarm for ${"%02d:%02d".format(Locale.UK, hour, minute)}${label?.let { " ($it)" } ?: ""}."
    }

    /** Hands a request to the Clock app. Android only lets an app do that while it is on screen. */
    private suspend fun openClock(intent: Intent) {
        if (!onScreen()) throw ToolException("The watch screen went off before the Clock app could be asked; try again with the screen on.")
        withContext(Dispatchers.Main) {
            try {
                beforeLeaving?.invoke()
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                throw ToolException("No app on this watch accepts timers or alarms from other apps; the wearer can set it in the Clock app.")
            } catch (e: SecurityException) {
                throw ToolException("The Clock app refused the request; the wearer can set it there directly.")
            }
        }
    }

    private fun onScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it-hour" },
            m.takeIf { it > 0 }?.let { "$it-minute" },
            s.takeIf { it > 0 }?.let { "$it-second" },
        ).joinToString(" ")
    }

    // ── Media ───────────────────────────────────────────────

    private suspend fun media(action: String, level: Int): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "volume_up", "volume_down", "set_volume" -> return volume(action, level)
            else -> throw ToolException("Unknown media action $action.")
        }
        // With notification access, steer the active player directly and say which it is.
        NotificationsService.mediaController(context)?.let { player ->
            val controls = player.transportControls
            when (key) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> controls.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> controls.pause()
                KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                else -> controls.skipToPrevious()
            }
            delay(MEDIA_SETTLE_MS)
            return "Sent $action. " + (NotificationsService.describeNowPlaying(context) ?: "")
        }
        // Otherwise a press is a key going down and coming up; it reaches the app that last played.
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
        return "Sent $action to the media player that last played on the watch. " +
            "If nothing has played since the watch started, nothing will respond."
    }

    /** One step up or down, or a percentage, applied to the wearer's own volume level. */
    private suspend fun volume(action: String, level: Int): String {
        if (action == "set_volume" && level !in 0..100) throw ToolException("set_volume needs a level from 0 to 100.")
        val change = { current: Int, max: Int ->
            when (action) {
                "volume_up" -> current + 1
                "volume_down" -> current - 1
                // Any level above nothing stays audible, however small.
                else -> if (level == 0) 0 else ((level * max + 50) / 100).coerceAtLeast(1)
            }
        }
        val (set, max) = withContext(Dispatchers.Main) {
            val audio = context.getSystemService(AudioManager::class.java)
            val stream = AudioManager.STREAM_MUSIC
            val max = audio.getStreamMaxVolume(stream)
            val set = speaker?.setUserVolume(change) ?: run {
                audio.setStreamVolume(stream, change(audio.getStreamVolume(stream), max).coerceIn(0, max), 0)
                audio.getStreamVolume(stream)
            }
            set to max
        }
        return "Media volume is now ${percent(set, max)}%." + if (set == 0) " That is muted, so a spoken reply won't be heard." else ""
    }

    // ── Replies and Do Not Disturb ──────────────────────────

    private suspend fun prepareReply(number: Int, text: String): String {
        if (text.isEmpty()) throw ToolException("There was no reply to send.")
        return NotificationsService.prepareReply(context, number, text) ?: throw notificationAccessOff()
    }

    private suspend fun doNotDisturb(on: Boolean, until: String?): String {
        val end = if (on && until != null) nextTime(until) else null
        val done = NotificationsService.setDoNotDisturb(context, on) ?: throw notificationAccessOff()
        if (end != null) {
            Reminders.scheduleDoNotDisturbEnd(context, end)
        } else {
            Reminders.cancelDoNotDisturbEnd(context)
        }
        return done + (end?.let { " It turns off at ${ukTime("HH:mm", it)}${if (isToday(it)) "" else " tomorrow"}." } ?: "")
    }

    /** The next moment the clock shows [hhmm]. */
    private fun nextTime(hhmm: String): Long {
        val time = runCatching { LocalTime.parse(hhmm) }.getOrNull() ?: throw ToolException("\"$hhmm\" isn't a time like 07:30.")
        var at = LocalDate.now().atTime(time)
        if (!at.isAfter(LocalDateTime.now())) at = at.plusDays(1)
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    // ── Electricity ─────────────────────────────────────────

    private suspend fun electricityPrices(): String {
        val json = getJson("https://api.porssisahko.net/v2/latest-prices.json")
        val array = json.optJSONArray("prices") ?: throw ToolException("The price service answered in an unexpected form.")
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        // Quarter-hour prices from now on, by the local hour they fall in.
        class Slot(val start: Long, val end: Long, val price: Double)
        val slots = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                Slot(Instant.parse(o.getString("startDate")).toEpochMilli(), Instant.parse(o.getString("endDate")).toEpochMilli(), o.getDouble("price"))
            }.getOrNull()
        }.filter { it.end > now }.sortedBy { it.start }
        if (slots.isEmpty()) throw ToolException("The price service has no prices from now on.")
        val current = slots.firstOrNull { it.start <= now }
        val hours = slots.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).truncatedTo(ChronoUnit.HOURS) }
            .map { (hour, list) -> hour to list.map { it.price }.average() }
        // The day by name past tomorrow: a market day ends at 01:00 Finnish time, so the last
        // hour can be the night after tomorrow.
        fun day(at: java.time.ZonedDateTime): String = when (ChronoUnit.DAYS.between(LocalDate.now(), at.toLocalDate())) {
            0L -> ""
            1L -> "tomorrow "
            else -> at.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.UK) + " "
        }
        fun label(hour: java.time.ZonedDateTime) = day(hour) + "%02d".format(Locale.UK, hour.hour)
        fun cents(price: Double) = "%.1f".format(Locale.UK, price + 0.0).replace("-0.0", "0.0")
        // The hour under way has only its remaining quarters; it doesn't compete for cheapest.
        val whole = hours.filter { (hour, _) -> hour.toInstant().toEpochMilli() >= now - 60_000 }.ifEmpty { hours }
        val cheapest = whole.minBy { it.second }
        val dearest = whole.maxBy { it.second }
        val last = Instant.ofEpochMilli(slots.last().end + 1).atZone(zone)
        return buildString {
            current?.let { append("Now ${cents(it.price)} c/kWh. ") }
            append("Hourly averages from now (hour: c/kWh): ")
            append(hours.joinToString("; ") { (hour, price) -> "${label(hour)}: ${cents(price)}" })
            append(". Cheapest whole hour ${label(cheapest.first)}:00 at ${cents(cheapest.second)}, dearest ${label(dearest.first)}:00 at ${cents(dearest.second)}. ")
            append("Prices known until ${day(last)}${"%02d:%02d".format(Locale.UK, last.hour, last.minute)}. ")
            append("Spot prices include VAT but not the retailer's margin or transmission. Source: porssisahko.net.")
        }
    }

    // ── Reminders ───────────────────────────────────────────

    private suspend fun addReminder(text: String, at: String?, inMinutes: Int?): String {
        if (text.isEmpty()) throw ToolException("There was nothing to be reminded of.")
        val time = when {
            inMinutes != null -> System.currentTimeMillis() + inMinutes * 60_000L
            at != null -> runCatching { LocalDateTime.parse(at).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
                ?: throw ToolException("\"$at\" isn't a date and time like 2026-10-04T17:00.")
            else -> throw ToolException("Say when: a time, or in how many minutes.")
        }
        if (time <= System.currentTimeMillis()) throw ToolException("That time has already passed.")
        val reminder = withContext(Dispatchers.IO) {
            try {
                Reminders.add(context, text.take(NoteStore.MAX_LENGTH), time)
            } catch (e: Reminders.Full) {
                throw ToolException("There are already ${Reminders.MAX_REMINDERS} reminders; cancel one first.")
            }
        } ?: throw ToolException("The reminder couldn't be saved on the watch.")
        val day = if (isToday(time)) "today" else ukTime("EEEE d MMMM", time)
        return "Reminder ${reminder.id} set for $day at ${ukTime("HH:mm", time)}: $text." + notificationsBlocked()
    }

    private suspend fun listReminders(): String {
        val pending = withContext(Dispatchers.IO) {
            try {
                Reminders.all(context)
            } catch (e: Exception) {
                throw ToolException("The reminders on the watch couldn't be read.")
            }
        }
        if (pending.isEmpty()) return "No reminders are set."
        return pending.joinToString(" ") { r ->
            val day = if (isToday(r.at)) "today" else ukTime("EEEE d MMMM", r.at)
            "Reminder ${r.id}, $day at ${ukTime("HH:mm", r.at)}: ${r.text}."
        }
    }

    private suspend fun cancelReminder(id: Int): String {
        val cancelled = withContext(Dispatchers.IO) { Reminders.cancel(context, id) }
        return if (cancelled) "Reminder $id cancelled." else throw ToolException("There is no reminder $id.")
    }

    /** A note for Claude if reminders can only buzz, not show their text. */
    private fun notificationsBlocked(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            " ClaudeWatch isn't allowed to show notifications, so the watch will only buzz; the wearer can allow notifications for ClaudeWatch in Settings, under Apps."
        } else {
            ""
        }

    private fun notificationAccessOff() = ToolException(
        "Notification access is off for ClaudeWatch. It is granted once, from a phone or computer over ADB; " +
            "ClaudeWatch's diagnostics (a long press on the microphone) show how."
    )

    private fun percent(value: Int, max: Int) = if (max == 0) 0 else (value * 100 + max / 2) / max

    // ── Barometer ───────────────────────────────────────────

    private suspend fun airPressure(): String {
        val sm = context.getSystemService(SensorManager::class.java)
        val reading = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { firstReading(sm, it) }?.get(0)
        val sensorLine = reading?.let { "The watch's barometer reads ${"%.1f".format(Locale.UK, it)} hPa." }
            ?: "This watch's barometer gave no reading."

        val here = orNull { recentLocation() }
            ?: return "$sensorLine Without the watch's location there is no sea-level comparison or altitude."
        val json = orNull {
            getJson(
                "https://api.open-meteo.com/v1/forecast?latitude=${"%.3f".format(Locale.UK, here.latitude)}&longitude=${"%.3f".format(Locale.UK, here.longitude)}" +
                    "&current=pressure_msl&hourly=pressure_msl&past_days=1&forecast_days=2&timezone=auto"
            )
        } ?: return "$sensorLine The weather service couldn't be reached for the sea-level pressure."

        val msl = json.getJSONObject("current").optDouble("pressure_msl")
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val values = hourly.getJSONArray("pressure_msl")
        val currentHour = json.getJSONObject("current").optString("time").take(13)
        val i = (0 until times.length()).firstOrNull { times.getString(it).take(13) >= currentHour }
        fun at(index: Int) = values.optDouble(index).takeUnless { it.isNaN() || index !in 0 until values.length() }
        val before = i?.let { at(it - 3) }
        val after = i?.let { at(it + 3) }
        if (msl.isNaN()) return "$sensorLine The weather service gave no sea-level pressure."
        val trend = buildString {
            append("Sea-level pressure here is ${msl.roundToInt()} hPa now")
            before?.let { append(", ${it.roundToInt()} three hours ago") }
            after?.let { append(", and expected to be ${it.roundToInt()} in three hours") }
            append(".")
        }
        val ground = json.optDouble("elevation").takeUnless { it.isNaN() }?.let { "; the ground there is about ${it.roundToInt()} m" } ?: ""
        val altitude = if (reading != null) {
            " From the two, the watch is about ${SensorManager.getAltitude(msl.toFloat(), reading).roundToInt()} m above sea level$ground."
        } else {
            ""
        }
        return "$sensorLine $trend$altitude Source: the watch's sensor and Open-Meteo."
    }

    // ── Compass ─────────────────────────────────────────────

    private suspend fun compass(): String {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: throw ToolException("This watch doesn't offer a compass to apps.")
        val latest = AtomicReference<FloatArray?>()
        val status = AtomicInteger(SensorManager.SENSOR_STATUS_UNRELIABLE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                latest.set(event.values.clone())
                status.set(event.accuracy)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = status.set(accuracy)
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        // Give the wearer a moment to hold still, then take the first reading with the watch flat,
        // or the last one if it never is.
        var flat: FloatArray? = null
        try {
            delay(COMPASS_SETTLE_MS)
            withTimeoutOrNull(COMPASS_FLAT_WAIT_MS) {
                while (flat == null) {
                    flat = latest.get()?.takeIf { isFlat(orientationOf(it)) }
                    if (flat == null) delay(100)
                }
            }
        } finally {
            sm.unregisterListener(listener)
        }
        val values = flat ?: latest.get() ?: throw ToolException("The compass gave no reading.")
        val orientation = orientationOf(values)
        var bearing = Math.toDegrees(orientation[0].toDouble())
        // True north rather than magnetic, when the watch knows roughly where it is.
        val here = lastKnownLocation()
        val northKind = if (here != null) {
            bearing += GeomagneticField(here.latitude.toFloat(), here.longitude.toFloat(), here.altitude.toFloat(), System.currentTimeMillis()).declination
            "true north"
        } else {
            "magnetic north"
        }
        val degrees = ((bearing % 360 + 360) % 360).roundToInt() % 360
        val notFlat = if (flat == null) {
            " The watch wasn't held flat, so the reading may be off; ask the wearer to hold it level and try again if it matters."
        } else {
            ""
        }
        // The rotation vector's own estimate of its heading error, where the watch gives one, is
        // more telling than the accuracy status, which many watches leave fixed.
        val errorDegrees = values.getOrNull(4)?.takeIf { sensor.type == Sensor.TYPE_ROTATION_VECTOR && it > 0f }
            ?.let { Math.toDegrees(it.toDouble()).roundToInt() }
        val unsure = errorDegrees?.let { it > COMPASS_MAX_ERROR_DEGREES } ?: (status.get() < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
        val calibration = if (unsure) {
            " The compass is unsure${errorDegrees?.let { " (within about $it°)" } ?: ""}; moving the wrist in a figure of eight a few times calibrates it."
        } else {
            ""
        }
        return "The watch's 12 o'clock edge points $degrees° from $northKind (${compassPoint(degrees)}).$notFlat$calibration"
    }

    /** Azimuth, pitch and roll in radians, from a rotation vector. */
    private fun orientationOf(rotationVector: FloatArray): FloatArray {
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, rotationVector)
        return SensorManager.getOrientation(matrix, FloatArray(3))
    }

    private fun isFlat(orientation: FloatArray) =
        abs(Math.toDegrees(orientation[1].toDouble())) <= FLAT_DEGREES && abs(Math.toDegrees(orientation[2].toDouble())) <= FLAT_DEGREES

    // ── Directions and navigation ───────────────────────────

    /** A place to go: its coordinates and a name to say. */
    private class Place(val latitude: Double, val longitude: Double, val name: String)

    private val placeCache = java.util.concurrent.ConcurrentHashMap<String, Place>()
    private val nominatimLock = kotlinx.coroutines.sync.Mutex()
    private var lastNominatim = 0L

    private suspend fun destination(query: String?, noteId: Int?, near: Location?): Place = when {
        noteId != null -> {
            val note = withContext(Dispatchers.IO) { runCatching { notes.all() }.getOrNull() }?.firstOrNull { it.id == noteId }
                ?: throw ToolException("There is no note $noteId.")
            val lat = note.latitude
            val lon = note.longitude
            if (lat == null || lon == null) throw ToolException("Note $noteId was saved without a place.")
            Place(lat, lon, "the place saved with note $noteId (${note.text})")
        }
        query != null -> findPlace(query, near)
        else -> throw ToolException("Say where to: a place, or a saved note.")
    }

    /**
     * Finds a place by name or address with OpenStreetMap's Nominatim, preferring results near
     * the wearer; towns and cities fall back to Open-Meteo's place search.
     */
    private suspend fun findPlace(query: String, near: Location?): Place {
        // A box of about 110 km around the wearer, preferred but not required; its centre is
        // rounded to a tenth of a degree, so OpenStreetMap learns only the area.
        val nearby = near?.let {
            val lat = Math.round(it.latitude * 10) / 10.0
            val lon = Math.round(it.longitude * 10) / 10.0
            "&viewbox=" + "%.1f,%.1f,%.1f,%.1f".format(Locale.US, lon - 1, lat + 0.5, lon + 1, lat - 0.5)
        } ?: ""
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=en&q=" +
            URLEncoder.encode(query, "UTF-8") + nearby
        val hit = placeCache[url] ?: orNull { nominatim(url).optJSONObject(0) }?.let { found ->
            Place(found.getString("lat").toDouble(), found.getString("lon").toDouble(), found.optString("display_name").split(", ").take(3).joinToString(", "))
                .also { placeCache[url] = it }
        }
        if (hit != null) return hit
        val (lat, lon, name) = geocode(query)
        return Place(lat, lon, name)
    }

    private suspend fun directions(query: String?, noteId: Int?): String {
        val here = currentLocation()
        val place = destination(query, noteId, here)
        val result = FloatArray(2)
        Location.distanceBetween(here.latitude, here.longitude, place.latitude, place.longitude, result)
        val bearing = ((result[1] % 360 + 360) % 360).roundToInt() % 360
        val precise = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        return "${place.name} is ${distance(result[0])} away as the crow flies, bearing $bearing° from true north " +
            "(${compassPoint(bearing)}) from the wearer. By road it is further." +
            (if (precise) "" else " The watch may give only an approximate location (to within about 2 km), so treat short distances as rough.") +
            " Source: the watch's location" + (if (noteId == null) " and OpenStreetMap (© OpenStreetMap contributors)." else ".")
    }

    /**
     * Gets navigation ready: finds the best app that will take the request and leaves the screen
     * to open as [pendingLaunch] once the reply has been spoken, so Claude neither talks over Maps
     * nor answers from the background.
     */
    private fun navigate(query: String?, noteId: Int?, mode: String): String {
        val code = when (mode) {
            "bicycle" -> "b"
            "drive" -> "d"
            else -> "w"
        }
        // A saved place goes as exact coordinates; a name goes to Maps as it is, for Maps to find.
        val (target, named) = if (noteId != null) {
            val note = runCatching { notes.all() }.getOrNull()?.firstOrNull { it.id == noteId }
                ?: throw ToolException("There is no note $noteId.")
            val lat = note.latitude
            val lon = note.longitude
            if (lat == null || lon == null) throw ToolException("Note $noteId was saved without a place.")
            "%.6f,%.6f".format(Locale.US, lat, lon) to "the place saved with note $noteId"
        } else {
            val name = query ?: throw ToolException("Say where to: a place, or a saved note.")
            name to name
        }
        val navigation = Uri.parse("google.navigation:q=" + Uri.encode(target, ",") + "&mode=" + code)
        val pm = context.packageManager
        val maps = Intent(Intent.ACTION_VIEW, navigation).setPackage(GOOGLE_MAPS)
        val anyNavigation = Intent(Intent.ACTION_VIEW, navigation)
        val map = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(target, ",")))
        val how = when (code) {
            "b" -> "cycling"
            "d" -> "driving"
            else -> "walking"
        }
        val (intent, result) = when {
            pm.resolveActivity(maps, 0) != null ->
                maps to "Google Maps opens with $how navigation to $named as soon as you finish speaking; keep the reply to a sentence."
            pm.resolveActivity(anyNavigation, 0) != null ->
                anyNavigation to "A navigation app (not Google Maps) opens with directions to $named as soon as you finish speaking; keep the reply to a sentence."
            pm.resolveActivity(map, 0) != null ->
                map to "No app here takes navigation requests, so only a map of $named opens when you finish speaking; say so, briefly."
            else -> throw ToolException("No maps app on this watch takes navigation requests; Google Maps can be installed from the watch's Play Store.")
        }
        pendingLaunch = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return result + if (noteId == null) " If Maps picks the wrong place, the wearer can change it there." else ""
    }

    // ── Notes ───────────────────────────────────────────────

    private suspend fun remember(text: String, atLocation: Boolean): String {
        if (text.isEmpty()) throw ToolException("There was nothing to remember.")
        // Without a location (no permission, no fix), the note is still worth keeping.
        var locationProblem: String? = null
        val where = if (atLocation) {
            try {
                currentLocation()
            } catch (e: ToolException) {
                locationProblem = e.message
                null
            }
        } else {
            null
        }
        val place = where?.let { placeName(it) }
        val note = withContext(Dispatchers.IO) {
            notes.add(text.take(NoteStore.MAX_LENGTH), where?.latitude, where?.longitude, place)
        } ?: throw ToolException("The note couldn't be saved on the watch.")
        return "Saved note ${note.id}" + (where?.let { " with the current location" + (place?.let { p -> ", in $p" } ?: "") } ?: "") + "." +
            (locationProblem?.let { " The place wasn't saved: $it" } ?: "")
    }

    private suspend fun recall(withDirections: Boolean): String {
        val saved = withContext(Dispatchers.IO) {
            try {
                notes.all()
            } catch (e: Exception) {
                throw ToolException("The notes on the watch couldn't be read.")
            }
        }.reversed()
        if (saved.isEmpty()) return "No notes are saved."
        val here = if (withDirections && saved.any { it.latitude != null }) orNull { currentLocation() } else null
        val lines = saved.map { n ->
            val whereSaved = if (n.latitude != null && n.longitude != null) {
                val directions = here?.let {
                    val result = FloatArray(2)
                    Location.distanceBetween(it.latitude, it.longitude, n.latitude, n.longitude, result)
                    val bearing = ((result[1] % 360 + 360) % 360).roundToInt() % 360
                    ", ${distance(result[0])} away, bearing $bearing° (${compassPoint(bearing)}) from the wearer"
                } ?: ""
                // Roughly where, for Claude; the exact point stays on the watch for directions.
                " Place saved" + (n.place?.let { " in $it" } ?: " at about ${"%.3f".format(Locale.UK, n.latitude)}, ${"%.3f".format(Locale.UK, n.longitude)}") +
                    directions + "."
            } else {
                ""
            }
            "Note ${n.id}, saved ${ukTime("EEEE d MMMM HH:mm", n.savedAt)}: ${n.text}.$whereSaved"
        }
        val noDirections = if (withDirections && here == null && saved.any { it.latitude != null }) {
            " The watch couldn't find its own location, so there are no directions."
        } else {
            ""
        }
        return lines.joinToString(" ") + noDirections
    }

    private suspend fun forget(ids: JSONArray?, all: Boolean): String = withContext(Dispatchers.IO) {
        if (all) {
            val count = notes.clear()
            if (count < 0) throw ToolException("The notes on the watch couldn't be changed.")
            return@withContext "Deleted all $count notes."
        }
        val wanted = (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optInt(it, -1)?.takeIf { id -> id > 0 } }.toSet()
        if (wanted.isEmpty()) throw ToolException("Say which notes to delete, by the ids recall gives.")
        val removed = notes.remove(wanted)
        if (removed < 0) throw ToolException("The notes on the watch couldn't be changed.")
        if (removed == 0) "No note had those ids." else "Deleted $removed note${if (removed == 1) "" else "s"}."
    }

    private fun distance(metres: Float): String =
        if (metres < 1000) "${(metres / 10).roundToInt() * 10} m" else "${"%.1f".format(Locale.UK, metres / 1000)} km"

    /** Eight points of the compass, for a bearing in degrees. */
    private fun compassPoint(degrees: Int): String =
        listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[((degrees + 22) % 360) / 45]

    // ── Helpers ─────────────────────────────────────────────

    /** The first value a sensor reports, within a few seconds. */
    private suspend fun firstReading(sm: SensorManager, sensor: Sensor): FloatArray? = withTimeoutOrNull(SENSOR_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    sm.unregisterListener(this)
                    if (cont.isActive) cont.resume(event.values.clone())
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            cont.invokeOnCancellation { sm.unregisterListener(listener) }
        }
    }

    /** The block's result, or null if it fails; cancellation still cancels. */
    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Where the watch was lately, without waiting for a fix; null if it doesn't know or may not say. */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = context.getSystemService(LocationManager::class.java)
        return lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** A location from the last half hour if there is one (pressure needs only the area), else a fresh fix. */
    private suspend fun recentLocation(): Location =
        lastKnownLocation()?.takeIf { System.currentTimeMillis() - it.time < RECENT_LOCATION_MS } ?: currentLocation()

    /** A refused permission becomes a message Claude passes on: what to allow, and where. */
    private fun requirePermission(permission: String, name: String) {
        if (!context.hasPermission(permission)) {
            throw ToolException("$name permission is off for ClaudeWatch. The wearer can allow it in the watch's Settings, under Apps.")
        }
    }

    /**
     * A search on OpenStreetMap's Nominatim, kept to its usage policy: at most one request a
     * second, results cached (see [placeCache]), and the app named in the User-Agent.
     */
    private suspend fun nominatim(url: String): JSONArray = nominatimLock.withLock {
        val wait = lastNominatim + 1_000 - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        try {
            getJsonArray(url)
        } finally {
            lastNominatim = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun getJsonArray(url: String): JSONArray = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            readTimeout = HTTP_READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "ClaudeWatch/1 (personal Wear OS app; https://github.com/vcorr/ClaudeWatch)")
        }
        try {
            if (conn.responseCode != 200) throw ToolException("The place search answered HTTP ${conn.responseCode}.")
            JSONArray(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            readTimeout = HTTP_READ_TIMEOUT_MS
        }
        try {
            if (conn.responseCode != 200) throw ToolException("The online service answered HTTP ${conn.responseCode}.")
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val LOCATION_TIMEOUT_MS = 15_000L
        private const val HEART_RATE_TIMEOUT_MS = 20_000L
        private const val STEPS_FLUSH_WAIT_MS = 1_500L
        private const val SENSOR_TIMEOUT_MS = 3_000L
        private const val COMPASS_SETTLE_MS = 1_500L
        private const val COMPASS_FLAT_WAIT_MS = 2_500L
        private const val COMPASS_MAX_ERROR_DEGREES = 20
        private const val FLAT_DEGREES = 30
        private const val MEDIA_SETTLE_MS = 700L
        private const val GOOGLE_MAPS = "com.google.android.apps.maps"
        private const val RECENT_LOCATION_MS = 30 * 60 * 1000L
        private const val MAX_EVENTS = 15
        private const val HTTP_CONNECT_TIMEOUT_MS = 10_000
        private const val HTTP_READ_TIMEOUT_MS = 15_000

        /** Android 16 replaced BODY_SENSORS with a heart-rate permission for apps that target it. */
        fun heartRatePermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

        /** Everything the tools may need, asked for together with the microphone. */
        fun permissions(): Array<String> = listOfNotNull(
            // Precise for directions and saved places; the wearer may still choose approximate.
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CALENDAR,
            heartRatePermission(),
            Manifest.permission.ACTIVITY_RECOGNITION,
            // For reminders to show their text.
            Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
        ).toTypedArray()

        private fun tool(name: String, description: String, properties: JSONObject = JSONObject(), required: List<String> = emptyList()) = JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "input_schema",
                JSONObject().put("type", "object").put("properties", properties)
                    .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) },
            )
    }
}

package com.vcorr.claudewatch

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Reminders the wearer asks Claude to set ("remind me at five to ring Mum"): kept in the app's
 * private files, each with an exact alarm that posts a notification and buzzes when it is due.
 * Alarms don't survive a reboot, so [BootReceiver] sets them again. Ids only ever grow.
 */
object Reminders {

    class Reminder(val id: Int, val text: String, val at: Long)

    private const val CHANNEL = "reminders"
    private const val ACTION_FIRE = "com.vcorr.claudewatch.REMINDER"
    private const val ACTION_DND_OFF = "com.vcorr.claudewatch.DND_OFF"
    private const val EXTRA_ID = "id"
    const val MAX_REMINDERS = 50

    /** Thrown by [add] when [MAX_REMINDERS] are already set. */
    class Full : Exception()
    // Request codes for the alarm that ends Do Not Disturb, apart from reminders' ids.
    private const val DND_REQUEST = -1

    private val LOCK = Any()

    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, "reminders.json"))

    /** Pending reminders, soonest first. Throws if the file can't be read. */
    fun all(context: Context): List<Reminder> = synchronized(LOCK) { read(context).second.sortedBy { it.at } }

    /** Saves and schedules a reminder; null if it couldn't be saved; throws [Full] at the limit. */
    fun add(context: Context, text: String, at: Long): Reminder? = synchronized(LOCK) {
        val (nextId, reminders) = runCatching { read(context) }.getOrNull() ?: return null
        if (reminders.size >= MAX_REMINDERS) throw Full()
        val reminder = Reminder(nextId, text, at)
        if (!write(context, nextId + 1, reminders + reminder)) return null
        schedule(context, reminder)
        reminder
    }

    /** Cancels a reminder; false if there was no such reminder or the change couldn't be saved. */
    fun cancel(context: Context, id: Int): Boolean = synchronized(LOCK) {
        val (nextId, reminders) = runCatching { read(context) }.getOrNull() ?: return false
        if (reminders.none { it.id == id }) return false
        if (!write(context, nextId, reminders.filterNot { it.id == id })) return false
        alarms(context).cancel(firePending(context, id))
        true
    }

    /** Sets every pending reminder's alarm again, as after a reboot; ones missed meanwhile fire now. */
    fun rescheduleAll(context: Context) {
        val pending = runCatching { all(context) }.getOrNull() ?: return
        pending.forEach { schedule(context, it) }
    }

    /** Ends Do Not Disturb at [at], replacing any earlier such alarm. */
    fun scheduleDoNotDisturbEnd(context: Context, at: Long) = setAlarm(context, at, dndPending(context))

    fun cancelDoNotDisturbEnd(context: Context) = alarms(context).cancel(dndPending(context))

    private fun schedule(context: Context, reminder: Reminder) =
        setAlarm(context, maxOf(reminder.at, System.currentTimeMillis() + 1_000), firePending(context, reminder.id))

    private fun setAlarm(context: Context, at: Long, operation: PendingIntent) {
        val alarms = alarms(context)
        // Exact if allowed (USE_EXACT_ALARM is granted at install); otherwise within a few minutes.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun firePending(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        id,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun dndPending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        DND_REQUEST,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_DND_OFF),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Shows a due reminder and removes it from the list. */
    private fun fire(context: Context, id: Int) {
        val reminder = synchronized(LOCK) {
            val (nextId, reminders) = runCatching { read(context) }.getOrNull() ?: return
            val due = reminders.firstOrNull { it.id == id } ?: return
            write(context, nextId, reminders.filterNot { it.id == id })
            due
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you asked Claude to set"
                enableVibration(true)
                vibrationPattern = VIBRATION
            }
        )
        // Before Android 13 posting needs no permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Reminder")
                .setContentText(reminder.text)
                .setStyle(Notification.BigTextStyle().bigText(reminder.text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(reminder.at)
                .setShowWhen(true)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_TAG, reminder.id, notification)
        } else {
            // Without permission to notify, at least buzz; the wearer can ask Claude what it was.
            Log.d(TAG, "reminder due without notification permission")
            // As an alarm: a buzz of unknown purpose from the background is ignored.
            context.getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createWaveform(VIBRATION, -1),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        }
    }

    private val VIBRATION = longArrayOf(0, 400, 200, 400, 200, 400)
    private const val NOTIFICATION_TAG = "reminder"

    private fun read(context: Context): Pair<Int, List<Reminder>> {
        val text = try {
            String(file(context).readFully())
        } catch (e: FileNotFoundException) {
            return 1 to emptyList()
        }
        val root = try {
            JSONObject(text).also { it.getJSONArray("reminders") }
        } catch (e: org.json.JSONException) {
            // A damaged list would block reminders for good: keep it aside and start afresh.
            Log.d(TAG, "reminders file unreadable; set aside")
            File(context.applicationContext.filesDir, "reminders.json").renameTo(File(context.applicationContext.filesDir, "reminders.damaged.json"))
            return 1 to emptyList()
        }
        val array = root.getJSONArray("reminders")
        val reminders = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Reminder(o.getInt("id"), o.getString("text"), o.getLong("at"))
        }
        return maxOf(root.optInt("nextId", 1), (reminders.maxOfOrNull { it.id } ?: 0) + 1) to reminders
    }

    private fun write(context: Context, nextId: Int, reminders: List<Reminder>): Boolean {
        val array = JSONArray()
        reminders.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) }
        return file(context).writeAll(JSONObject().put("nextId", nextId).put("reminders", array).toString().toByteArray())
    }

    /** Receives the app's own alarms: a reminder falling due, or Do Not Disturb ending. Not exported. */
    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_FIRE -> fire(context, intent.getIntExtra(EXTRA_ID, -1))
                ACTION_DND_OFF -> {
                    // Reaching the notification listener may take a moment; keep the broadcast alive meanwhile.
                    val result = goAsync()
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            NotificationsService.setDoNotDisturb(context.applicationContext, on = false)
                        } catch (e: Exception) {
                            Log.d(TAG, "ending Do Not Disturb failed: ${e.javaClass.simpleName}")
                        } finally {
                            result.finish()
                        }
                    }
                }
            }
        }
    }

    /** Sets reminders' alarms again after the watch restarts. Exported only for the boot broadcast. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) rescheduleAll(context)
        }
    }
}

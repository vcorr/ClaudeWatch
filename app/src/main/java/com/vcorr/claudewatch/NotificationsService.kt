package com.vcorr.claudewatch

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

/**
 * Notification access, which the wearer grants once (see [grantCommand]): lets Claude read the
 * watch's current notifications and see and steer what is playing, when asked. Nothing is kept or
 * logged; the system binds this service while access is granted, and the tools read through it.
 */
class NotificationsService : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var connected: NotificationsService? = null

        private const val CONNECT_WAIT_MS = 3_000L
        private const val MAX_NOTIFICATIONS = 15
        private const val MAX_FIELD = 300
        private const val MAX_MESSAGES = 5

        fun component(context: Context) = ComponentName(context, NotificationsService::class.java)

        fun granted(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
        }.getOrDefault(false)

        /** The shell command that grants access, for Bugjaeger or `adb shell`. */
        fun grantCommand(context: Context) = "cmd notification allow_listener ${component(context).flattenToString()}"

        /** The running service, asking the system to bind it if access is granted but it isn't yet. */
        private suspend fun service(context: Context): NotificationsService? {
            connected?.let { return it }
            if (!granted(context)) return null
            runCatching { NotificationListenerService.requestRebind(component(context)) }
            return withTimeoutOrNull(CONNECT_WAIT_MS) {
                while (connected == null) delay(100)
                connected
            }
        }

        /**
         * The notifications on the watch now, newest first, as plain lines for Claude: the app, when,
         * the title and text (the last few messages of a conversation). Null without access.
         */
        suspend fun describeActive(context: Context): String? {
            val service = service(context) ?: return null
            val all = runCatching { service.activeNotifications?.toList() }.getOrNull().orEmpty()
            val groupsWithChildren = all.filter { !it.isSummary() }.mapNotNull { it.groupKey }.toSet()
            val shown = all
                .filter { it.packageName != context.packageName }
                .filter { it.notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) == 0 }
                // A group's summary repeats its children; keep it only when it stands alone.
                .filterNot { it.isSummary() && it.groupKey in groupsWithChildren }
                .sortedByDescending { it.postTime }
            if (shown.isEmpty()) return "There are no notifications on the watch."
            val lines = shown.take(MAX_NOTIFICATIONS).mapNotNull { describe(context, it) }
            val more = shown.size - MAX_NOTIFICATIONS
            return lines.joinToString("\n") + if (more > 0) "\n…and $more older ones." else ""
        }

        private fun StatusBarNotification.isSummary() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

        private fun describe(context: Context, sbn: StatusBarNotification): String? {
            val extras = sbn.notification.extras ?: Bundle()
            val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim()
            val messages = messages(extras)
            val text = messages
                ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("; ")
            if (title.isNullOrBlank() && text.isNullOrBlank()) return null
            val app = appName(context, sbn)
            return "[$app, ${whenPosted(sbn.postTime)}] ${title.orEmpty().take(MAX_FIELD)}" +
                (text?.takeIf { it.isNotBlank() }?.let { ": ${it.take(MAX_FIELD * 2)}" } ?: "")
        }

        /** The last few messages of a messaging-style notification, "sender: text"; null if it isn't one. */
        private fun messages(extras: Bundle): String? {
            val bundles: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            }
            if (bundles.isNullOrEmpty()) return null
            return bundles.takeLast(MAX_MESSAGES).mapNotNull { (it as? Bundle)?.let(::message) }
                .joinToString(" / ")
                .ifBlank { null }
        }

        private fun message(bundle: Bundle): String? {
            val text = bundle.getCharSequence("text")?.toString()?.trim()?.take(MAX_FIELD) ?: return null
            val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bundle.getParcelable("sender_person", android.app.Person::class.java)?.name
            } else {
                @Suppress("DEPRECATION")
                (bundle.getParcelable("sender_person") as? android.app.Person)?.name
            } ?: bundle.getCharSequence("sender")
            return if (sender.isNullOrBlank()) text else "$sender: $text"
        }

        /**
         * The app a notification is from. Notifications bridged from the phone may be posted by a
         * system app on the watch's behalf, with the original app's name given separately.
         */
        private fun appName(context: Context, sbn: StatusBarNotification): String {
            sbn.notification.extras?.getCharSequence("android.substName")?.let { return it.toString() }
            return packageLabel(context, sbn.packageName)
        }

        private fun whenPosted(millis: Long): String {
            val day = java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
            return when (day) {
                LocalDate.now() -> ukTime("HH:mm", millis)
                LocalDate.now().minusDays(1) -> "yesterday " + ukTime("HH:mm", millis)
                else -> ukTime("EEEE d MMMM HH:mm", millis)
            }
        }

        /** The media session most recently active, if access is granted; null otherwise or if none. */
        suspend fun mediaController(context: Context): MediaController? {
            if (service(context) == null) return null
            return runCatching {
                context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(context)).firstOrNull()
            }.getOrNull()
        }

        /** What is playing, for Claude; null without access. */
        suspend fun describeNowPlaying(context: Context): String? {
            if (service(context) == null) return null
            val controller = mediaController(context) ?: return "Nothing is playing, and no media app is active."
            val meta = controller.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val state = when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_BUFFERING -> "loading"
                PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> "stopped"
                else -> "in an unknown state"
            }
            val what = listOfNotNull(title?.let { "\"$it\"" }, artist?.let { "by $it" }).joinToString(" ").ifBlank { "something untitled" }
            return "${packageLabel(context, controller.packageName)} has $what, $state."
        }

        private fun packageLabel(context: Context, pkg: String): String = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}

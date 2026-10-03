package com.vcorr.claudewatch

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
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
        // Notification.EXTRA_SUBSTITUTE_APP_NAME, which is hidden from apps.
        private const val SUBSTITUTE_APP_NAME = "android.substName"
        // Notification.EXTRA_BUILDER_APPLICATION_INFO, likewise hidden.
        private const val BUILDER_APPLICATION_INFO = "android.appInfo"

        fun component(context: Context) = ComponentName(context, NotificationsService::class.java)

        fun granted(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
        }.getOrDefault(false)

        /** The shell command that grants access, for Bugjaeger or `adb shell`. */
        fun grantCommand(context: Context) = "cmd notification allow_listener ${component(context).flattenToString()}"

        /**
         * The running service, asking the system to bind it if access is granted but it isn't yet.
         * Null without access; throws if access is granted but the system doesn't connect it.
         */
        private suspend fun service(context: Context): NotificationsService? {
            connected?.let { return it }
            if (!granted(context)) return null
            runCatching { NotificationListenerService.requestRebind(component(context)) }
            return withTimeoutOrNull(CONNECT_WAIT_MS) {
                while (connected == null) delay(100)
                connected
            } ?: throw WatchTools.ToolException("Notification access is granted, but the watch hasn't connected it yet; try again in a moment.")
        }

        /**
         * The notifications on the watch now, newest first, as plain lines for Claude: the app, when,
         * the title and text (the last few messages of a conversation). Null without access.
         */
        suspend fun describeActive(context: Context): String? {
            val service = service(context) ?: return null
            val all = try {
                service.activeNotifications?.toList().orEmpty()
            } catch (e: Exception) {
                throw WatchTools.ToolException("The watch wouldn't hand over its notifications just now.")
            }
            val groupsWithChildren = all.filter { !it.isSummary() }.mapNotNull { it.groupKey }.toSet()
            val shown = all
                .filter { it.packageName != context.packageName }
                .filter { it.notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) == 0 }
                // A group's summary repeats its children; keep it only when it stands alone.
                .filterNot { it.isSummary() && it.groupKey in groupsWithChildren }
                .sortedByDescending { it.postTime }
            if (shown.isEmpty()) return "There are no notifications on the watch."
            val described = shown.mapNotNull { describe(context, it) }
            if (described.isEmpty()) return "There are no notifications with any text on the watch."
            val lines = described.take(MAX_NOTIFICATIONS)
            val more = described.size - MAX_NOTIFICATIONS
            return lines.joinToString("\n") + if (more > 0) "\n…and $more older ones." else ""
        }

        private fun StatusBarNotification.isSummary() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

        private fun describe(context: Context, sbn: StatusBarNotification): String? {
            val extras = sbn.notification.extras ?: Bundle()
            // A group chat's name says more than the latest sender's.
            val title = listOf(Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TITLE)
                .firstNotNullOfOrNull { extras.getCharSequence(it)?.toString()?.trim()?.ifBlank { null } }
            // An inbox's lines over its summary ("3 new messages"); blank text counts as none.
            val text = messages(extras)
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("; ")?.ifBlank { null }
                ?: listOf(Notification.EXTRA_BIG_TEXT, Notification.EXTRA_TEXT)
                    .firstNotNullOfOrNull { extras.getCharSequence(it)?.toString()?.trim()?.ifBlank { null } }
            if (title.isNullOrBlank() && text.isNullOrBlank()) return null
            val app = appName(context, sbn)
            return "[$app, ${whenPosted(sbn.postTime)}] ${title.orEmpty().take(MAX_FIELD)}" +
                (text?.takeIf { it.isNotBlank() }?.let { ": ${it.take(MAX_FIELD * 2)}" } ?: "")
        }

        /** The last few messages of a messaging-style notification, "sender: text"; null if it isn't one. */
        private fun messages(extras: Bundle): String? {
            // The typed getters are unreliable on API 33, as AndroidX's BundleCompat notes.
            val bundles: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            }
            if (bundles.isNullOrEmpty()) return null
            // A message with no sender is the wearer's own.
            val self = person(extras, Notification.EXTRA_MESSAGING_PERSON)?.name
                ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)
                ?: "the wearer"
            return bundles.takeLast(MAX_MESSAGES).mapNotNull { (it as? Bundle)?.let { b -> message(b, self) } }
                .joinToString(" / ")
                .ifBlank { null }
        }

        private fun message(bundle: Bundle, self: CharSequence): String? {
            val text = bundle.getCharSequence("text")?.toString()?.trim()?.take(MAX_FIELD) ?: return null
            val sender = person(bundle, "sender_person")?.name ?: bundle.getCharSequence("sender")
            return "${if (sender.isNullOrBlank()) self else sender}: $text"
        }

        private fun person(bundle: Bundle, key: String): Person? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                bundle.getParcelable(key, Person::class.java)
            } else {
                @Suppress("DEPRECATION")
                bundle.getParcelable(key) as? Person
            }

        /**
         * The app a notification is from. Notifications bridged from the phone may be posted by a
         * system app on the watch's behalf, with the original app's name given separately.
         */
        private fun appName(context: Context, sbn: StatusBarNotification): String {
            val extras = sbn.notification.extras
            extras?.getCharSequence(SUBSTITUTE_APP_NAME)?.let { return it.toString() }
            // The poster's own app info travels with the notification, so no package lookup is needed.
            val info = extras?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    it.getParcelable(BUILDER_APPLICATION_INFO, ApplicationInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelable(BUILDER_APPLICATION_INFO) as? ApplicationInfo
                }
            }
            info?.let { runCatching { context.packageManager.getApplicationLabel(it).toString() }.getOrNull() }?.let { return it }
            return packageLabel(context, sbn.packageName)
        }

        /**
         * Which packages post the current notifications, and whether each names another app (as
         * bridged phone notifications may): for the diagnostics, no content.
         */
        suspend fun describeSources(context: Context): String? {
            val service = runCatching { service(context) }.getOrNull() ?: return null
            val all = runCatching { service.activeNotifications?.toList() }.getOrNull() ?: return null
            if (all.isEmpty()) return "no notifications now"
            return all.groupBy { it.packageName }.entries.joinToString("; ") { (pkg, list) ->
                val named = list.count { it.notification.extras?.getCharSequence(SUBSTITUTE_APP_NAME) != null }
                "$pkg ×${list.size}" + if (named > 0) " ($named name another app)" else ""
            }
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

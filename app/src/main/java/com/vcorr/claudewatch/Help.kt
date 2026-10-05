package com.vcorr.claudewatch

import android.Manifest
import android.content.Context
import android.os.Build

/**
 * ClaudeWatch's knowledge of itself, for the get_claudewatch_help tool: what it can do, with
 * example requests, how it is used, its limits, and which features are switched on for this watch
 * now. Kept out of the system prompt, which goes with every request, and fetched only when the
 * wearer asks what Claude can do or why something doesn't work.
 */
object Help {

    fun describe(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        fun on(permission: String) = if (context.hasPermission(permission)) "on" else "OFF (allow it in the watch's Settings, under Apps, ClaudeWatch, Permissions)"
        val notificationsShown = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) "on" else on(Manifest.permission.POST_NOTIFICATIONS)
        val notificationAccess = if (NotificationsService.granted(context)) {
            "on"
        } else {
            "OFF (granted once over ADB with a command the diagnostics show; long-press the microphone)"
        }
        val language = if (Language.finnish) "Finnish" else "English, because the watch can't both hear and speak Finnish just now"
        val location = if (context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) "on" else on(Manifest.permission.ACCESS_COARSE_LOCATION)

        return """
ClaudeWatch $version: a voice chat with Claude (Anthropic's Claude Haiku model) on the wearer's Galaxy Watch, a personal sideloaded app, not an official Anthropic or Samsung product. The conversation is in $language.

Using it: opening it (the app, its Talk tile, or a button shortcut the wearer may have set) starts listening at once. Tap to send early, hold to cancel; while Claude speaks, tap anywhere to stop it. After a reply it listens briefly for a follow-up. A chat is remembered for 30 minutes; "New" starts afresh; "Type" is for typing instead. Long-pressing the microphone opens the diagnostics.

What Claude can do here, with example requests:
- Talk, explain, translate, help think things through, from its own knowledge.
- Weather and forecast, here or anywhere ("will it rain this afternoon?"). Location ${location}.
- Where am I, the watch's battery, connection and next alarm.
- Heart rate now, measured on request (keep still); heart rate permission ${on(WatchTools.heartRatePermission())}. Steps, distance, calories and floors today; physical activity permission ${on(Manifest.permission.ACTIVITY_RECOGNITION)}.
- Calendar for up to a week ahead ("what's on tomorrow?"). Calendar permission ${on(Manifest.permission.READ_CALENDAR)}.
- Timers and alarms through the Clock app ("ten-minute timer", "wake me at 6:30").
- Reminders that buzz and show a notification ("remind me at five to ring Mum"), listed and cancelled on request. Showing notifications ${notificationsShown}.
- Music: play, pause, next, previous, volume; what's playing.
- Notifications and messages: read them ("any messages?"), reply to one (Claude reads the reply back and sends it only when the wearer confirms), Do Not Disturb on or off, optionally until a time. Notification access ${notificationAccess}.
- Directions: how far and which way a place is ("how far is Tampere?"), and turn-by-turn navigation in Google Maps ("walk me to the station", "take me back to the car" from a saved place).
- Notes kept on the watch, optionally with the place ("remember where I parked"), with distance and direction back to it later; compass ("which way is north?"); air pressure trend and altitude.
- Finland's electricity spot prices ("when is electricity cheapest tonight?").
- Web search, only when the wearer asks to look something up; each search costs about one cent.

What it can't do: phone calls or texts (the watch has no mobile plan; replies go through apps' notifications instead), Samsung Health history such as sleep, blood oxygen or ECG (Samsung keeps those to partner apps), changing system settings such as Wi-Fi, seeing anything on the phone except notifications it passes to the watch, or remembering chats beyond 30 minutes.
""".trim()
    }
}

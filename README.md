# ClaudeWatch

Voice chat with Claude (Haiku 5.5) on a Samsung Galaxy Watch. Open it, speak, and Claude answers aloud. It can also read the watch, act on it, and look a few things up. One user, sideloaded, using your own Anthropic API key. Plans and decisions are in [`docs/PLAN.md`](docs/PLAN.md).

## What you can ask

| | For example | Needs |
|---|---|---|
| Conversation | "Explain…", "Translate…", follow-up questions | — |
| Weather | "Will it rain this afternoon?", "Weather in Oulu tomorrow" | Location |
| Where you are | "Where am I?" | Location |
| Watch | "How's my battery?", "When's my next alarm?" | — |
| Heart rate | "What's my pulse?" (measured now; keep still) | Heart rate |
| Activity | "How far have I walked today?" (steps, distance, calories, floors) | Physical activity |
| Calendar | "What's on tomorrow?" (up to a week ahead) | Calendar |
| Timers and alarms | "Ten-minute timer", "Wake me at 6:30" (in the Clock app) | — |
| Reminders | "Remind me at five to ring Mum"; list and cancel them | Notifications |
| Music | "Pause", "Next song", "Volume to 30%", "What's playing?" | — (what's playing: notification access) |
| Messages | "Any messages?", "Reply to Anna: on my way" (read back, sent only when you confirm) | Notification access |
| Do Not Disturb | "Silence the watch until seven" | Notification access |
| Directions | "How far is Tampere, and which way?" (as the crow flies) | Location |
| Navigation | "Walk me to the station", "Take me back to the car" (opens Google Maps after the reply) | Precise location |
| Notes | "Remember I parked on level 3, here"; later "How far is my car?" | Location, if saving the place |
| Compass and barometer | "Which way is north?", "What's the air pressure doing?", "How high up am I?" | — |
| Electricity | "When is electricity cheapest tonight?" (Finnish spot prices, c/kWh incl. VAT) | — |
| Web search | "Look up…" (only when you ask) | — |
| Help | "What can you do?", "Why can't you read my messages?" | — |

Conversations are in Finnish if the watch can both recognise and speak Finnish, otherwise in English.

## Using it

- **Open:** the app, its **Talk** tile, or a button shortcut (Settings → Advanced features → Customise buttons → Double press → ClaudeWatch). It starts listening straight away.
- **While listening:** tap to send, hold to cancel. **While Claude talks:** tap anywhere to stop.
- After a reply it listens briefly for a follow-up, then closes itself.
- A chat is remembered for 30 minutes. **New** starts afresh; **Type** is for typing.
- **Long-press the microphone** for diagnostics: version, speech and voice checks, notification access, an API round trip.

## Install

You need developer options on the watch and an ADB app on the phone (Bugjaeger or Wear Installer 2).

1. **Watch:** Settings → About watch → Software information, tap **Software version** five times. In **Developer options**, turn on **ADB debugging** and **Wireless debugging**. Use the same Wi-Fi as the phone.
2. **Phone:** from the latest green run on the [Actions page](https://github.com/vcorr/ClaudeWatch/actions), download `ClaudeWatch-0.4.N` and unzip it.
3. In the ADB app, pair with the watch, then install the APK through the app. A phone can't install a watch APK directly.
4. **API key:** open ClaudeWatch. It shows an address and a PIN; open the address in the phone's browser, paste the key and PIN, and tap **Save to watch**.
5. Allow the permissions it asks for on first use. Each feature uses only its own permission, only when asked.

Each build has a new signing key, so **uninstall the old version before installing a new one**, then set the key again.

### Notification access (optional)

Needed for messages, replies, Do Not Disturb and what's playing. Android doesn't let sideloaded apps switch this on in Settings, so grant it once per install in the ADB app's shell:

```sh
cmd notification allow_listener com.vcorr.claudewatch/com.vcorr.claudewatch.NotificationsService
```

### From a Mac instead

```sh
brew install android-platform-tools
adb pair <watch-ip>:<pairing-port>
adb connect <watch-ip>:<debug-port>
adb install ClaudeWatch-0.4.N.apk
```

## Privacy and cost

- Your questions, and any watch data a question needs (location, a notification's text, your pulse), go to Anthropic's API. Weather and pressure lookups send approximate coordinates to Open-Meteo; a destination you name, and roughly where you are (to about 10 km), go to OpenStreetMap's Nominatim (place data © OpenStreetMap contributors); electricity prices send nothing about you; place names come from the watch's own geocoder.
- Notes, reminders and the 30-minute chat stay in the app's private storage, excluded from backups. Nothing is logged with content.
- The key is never built into the app. The setup page exists only while the watch shows it, needs the PIN, locks after five wrong tries, and is unencrypted on your Wi-Fi, so set it up at home.
- You pay Anthropic per use. Web search costs extra ($10 per 1,000 searches, per [Anthropic's docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool)), so it runs only when asked. Setting a monthly spending limit in the Anthropic Console is wise.

## Limits

- No calls or texts: the watch has no mobile plan. Messages are answered through apps' notifications.
- No Samsung Health history (sleep, blood oxygen, ECG): those stay in Samsung's apps.
- No system settings such as Wi-Fi or Bluetooth.
- Speech recognition and most features need a connection, through the phone, Wi-Fi or LTE.

## Building

There is no local build. GitHub Actions builds every push and attaches the APK; `:core` holds the plain-Kotlin parts and their tests. Background reading is in [`docs/RESEARCH.md`](docs/RESEARCH.md).

# ClaudeWatch build plan

**Goal:** a Claude voice chat on a Samsung Galaxy Watch, for carrying on a conversation when the phone and computer are out of reach. Speak, hear Claude answer, keep going hands-free. English only. One user, sideloaded; no Play Store release.

**Not goals:** replacing Gemini as the system assistant; syncing with claude.ai conversations (no public API for that); agents, health data or avatars; a phone companion app.

## Constraints that shape the plan

- **No local Android build.** This development container cannot reach Google's Maven repository, so every build is proven only by GitHub Actions. Each step keeps CI green. Pure logic lives in a plain Kotlin `core` module with JVM unit tests run in CI. UI is checked with Robolectric screenshot tests, whose PNGs CI uploads.
- **Only the owner can test on the watch.** Every phase ends with a device checklist. No phase builds on a device assumption the previous phase hasn't verified.
- **The repository is public.** Anything CI uploads is downloadable, and no secret may ever enter the source or an APK.
- **Connectivity is unknown.** "Away from the phone" only works if the watch has LTE or Wi-Fi of its own; a Bluetooth-only watch has no network out of phone range. *Open question for the owner.*
- **The watch model is unknown.** ClawWatch reports that Galaxy Watch 4–6 microphones struggle. *Open question for the owner.*
- **AGPL:** ClawWatch is a source of ideas only (`CLAWWATCH-NOTES.md`).

## Phase 0a — Stop the key leak (urgent, its own change)

- **Owner, before any code:** rotate the exposed key, delete the old artifacts, and create the new key in its own Console workspace with a monthly spending limit.
- Remove `-PCLAUDE_API_KEY` from CI and the `BuildConfig` field.
- The app reads the key from a private file `files/api_key`. The owner provisions it with `adb shell run-as com.vcorr.claudewatch sh -c 'cat > files/api_key' < key.txt`: debug builds allow `run-as`, there is no exported entry point, and the key never appears in a command line. If the key is missing, the app shows how to set it.
- `android:allowBackup="false"`; never log the key, request headers, prompts or replies.

*Device checklist:* install, provision the key, confirm a typed question still gets an answer.

## Phase 0b — Builds that install over each other, and tests

- **Stable signing:** a keystore stored as base64 CI secrets and used for debug builds of both modules. CI falls back to default debug signing when the secrets are absent (forks, Dependabot) so builds stay green. The owner keeps an offline backup of the keystore; losing it forces an uninstall, which wipes chats and the key. The first stable-signed install needs one uninstall.
- `versionCode` from `GITHUB_RUN_NUMBER`, so builds can be told apart.
- New `core` module (plain Kotlin, no Android): API request builder, reply parsing, history trimming, and later the SSE parser and sentence splitter. Uses kotlinx-serialization instead of `org.json`, which can't run in JVM unit tests. CI runs the unit tests.
- The `mobile` module becomes a thin phone test harness over `core`, useful for trying the voice loop on a phone; it is never shipped.

## Phase 0c — Compose for Wear OS (its own change)

- Rebuild the one existing screen in Compose for Wear OS Material 3, with the `org.jetbrains.kotlin.plugin.compose` plugin pinned to the same 2.4.20 as Kotlin, and library versions pinned in the catalogue.
- Robolectric + Roborazzi screenshot tests of each screen state, uploaded by CI. These replace the local previews we can't run.

*Device checklist (0b+0c):* uninstall once; install; provision the key; ask a typed question; install the next CI build over it and confirm the key survived.

## Phase 1a — Device probe

A diagnostics screen in the same APK. The manifest declares `<queries>` for `android.speech.RecognitionService` and `android.intent.action.TTS_SERVICE`, because otherwise API 30+ hides them and the probe reports false negatives. The probe asks for microphone permission first, then reports:
- `SpeechRecognizer.isRecognitionAvailable`, the on-device variant, and the recognition services it can see;
- whether `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` resolves, and to which app;
- the TTS engines and voices;
- a 5-second microphone test through each speech route, with a live transcript;
- one tiny API call's round-trip time and the active network transport (Bluetooth via phone, Wi-Fi or LTE).

The owner runs it twice, once with the phone's Bluetooth off, and sends screenshots. That settles the speech route and whether away-from-phone use is possible.

## Phase 1b — Hands-free voice loop

- A `SpeechInput` interface with two implementations: `SpeechRecognizer` (in-app, with live partial transcript) and `RecognizerIntent` (system dialog, relaunched automatically after each reply; the dialog's own silence timeout acts as the follow-up window). The probe's result picks the default. Vosk only if both fail.
- The state machine: IDLE → LISTENING → THINKING → SPEAKING. A tap interrupts at any point, and each turn carries a token so stale callbacks are ignored. After the reply finishes speaking (TTS `onDone` for the final utterance), a 3.5-second follow-up window opens with a visible countdown.
- **Launching the app goes straight into listening.** With the side button's double press mapped to the app, this is the Gemini-like gesture.
- Haptic tick when listening starts and stops. The microphone opens about 200 ms after speech ends, so it doesn't hear Claude's last word.
- Haiku 4.5, `max_tokens` 1,024 (brevity comes from the prompt; a reply that hits the cap is spoken up to its last full sentence), spoken-style system prompt. In-memory conversation.

*Device checklist:* a five-turn conversation without touching the screen after launch; interrupt Claude mid-sentence; let the follow-up window lapse.

## Phase 1c — Survive the wrist dropping

People lower their wrist to listen, which turns the screen off. The activity stops and the watch face returns.
- Support ambient mode (`AmbientLifecycleObserver`) so the app stays the visible activity.
- Run each turn's speech output in a foreground service of type `mediaPlayback`, tied to an Ongoing Activity, so a reply keeps playing with the wrist down.
- The microphone opens only while the app is visible. Follow-up listening with the wrist down is attempted only through a `microphone`-type foreground service started while the app was visible; that is verified on the device, not assumed.

*Device checklist:* drop the wrist mid-reply, and the reply finishes; raise it, and the conversation carries on.

## Phase 2 — Conversations that persist

- One JSON file per conversation, written with `AtomicFile` so a crash can't corrupt it; reopen the latest on launch; "New chat" and "Delete all chats".
- History sent each turn is trimmed in user/assistant *pairs*, always starting with a user message.
- Scrollable transcript with rotary-crown scrolling; replies stored in full.
- Errors: one retry after `retry-after` on 429/529; plain messages for no network, bad key, out of credit.
- Model setting on the watch (Haiku 4.5 default, Sonnet 5.5 option).
- **Decision:** keep our own HTTP client. The official `anthropic-java` SDK is not documented for Android and brings Jackson, R8 rules and size. Our client is small and fully unit-testable. This departs from Anthropic's usual "use the SDK" advice, deliberately.

*Device checklist:* chat, force-stop, reopen, carry on; new chat; scroll a long reply with the crown; airplane mode gives a clear message.

## Phase 3 — Faster replies

- Stream the reply (server-sent events); give each complete sentence to TTS with `QUEUE_ADD` and its own utterance ID; the follow-up window starts on `onDone` of the final ID.
- Request audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) while speaking; abandon it before listening.
- Interrupting calls `tts.stop()`, cancels the job and disconnects the request.
- Unit tests for the SSE parser and the sentence splitter (abbreviations, decimals, "e.g.").
- Measure the time from end of speech to first spoken word, before and after, on the device.

*Device checklist:* first words arrive noticeably sooner than in Phase 2; interrupting mid-stream stops both the speech and the network request.

## Phase 4 — Quick access

- A tile with a "Talk" button.
- README instructions for mapping the side button's double press to the app.

## Phase 5 — Optional extras (each stands alone)

- Web search through Claude's server-side search tool.
- Experiment: can the app be chosen as the default assistant and launched by holding the side button?
- Bluetooth earbuds: speech output and the earbuds' microphone.
- Per-conversation token and cost display.

## Decisions log

| Decision | Choice | Why |
|---|---|---|
| Key handling | Private file, provisioned with `run-as`; never in source or APK | Public repo; no exported surface |
| Speech in | `SpeechInput` interface: `SpeechRecognizer` or `RecognizerIntent`, chosen by the probe; Vosk last | `RecognizerIntent` is the documented Wear OS route; `SpeechRecognizer` has a known Galaxy Watch 4 failure ([flutter#130576](https://github.com/flutter/flutter/issues/130576)) |
| Model | Haiku 4.5 default, `max_tokens` 1,024 | Fastest and cheapest ($1 / $5 per million input / output tokens); brevity from the prompt, not the cap |
| HTTP | Own client in `core` | SDK not documented for Android; testability |
| Storage | `AtomicFile` JSON | Small data; crash-safe |
| UI | Compose for Wear OS Material 3, Phase 0c | Recommended toolkit; screenshot-tested in CI |
| Phone companion | Cut | One user with ADB doesn't need it |
| Prompt caching | Not used | Conversations rarely pass Haiku 4.5's 4,096-token minimum |

## Open questions for the owner

1. Does the watch have LTE or its own Wi-Fi, and which Galaxy Watch model is it?
2. How are APKs installed today: ADB from a computer, or an app on the phone?

# ClaudeWatch build plan

**Goal:** a Claude voice chat on a Samsung Galaxy Watch, for carrying on a conversation when the phone and computer are out of reach. Speak, hear Claude answer, keep going hands-free. English only. One user, sideloaded; no Play Store release.

**Not goals:** replacing Gemini as the system assistant; syncing with claude.ai conversations (no public API for that); agents, health data or avatars; a phone companion app.

## Constraints that shape the plan

- **No local Android build.** This development container cannot reach Google's Maven repository, so every build is proven only by GitHub Actions. Each step keeps CI green. Pure logic lives in a plain Kotlin `core` module with JVM unit tests run in CI. UI is checked with Robolectric screenshot tests, whose PNGs CI uploads.
- **Only the owner can test on the watch.** Every phase ends with a device checklist. No phase builds on a device assumption that hasn't been verified.
- **The repository is public.** Anything CI uploads is downloadable, and no secret may ever enter the source or an APK.
- **Connectivity:** Galaxy Watch 9 LTE, but with no mobile plan. Away from the phone it therefore works on known Wi-Fi only; anywhere else it needs the phone within Bluetooth range. Adding an eSIM plan later would remove that limit without any change to the app.
- **Watch model:** Galaxy Watch 9. ClawWatch's microphone warning concerns the Watch 4–6, so it should not apply, but Phase 1 checks.
- **Development machine:** a Mac, installing over adb (README).
- **AGPL:** ClawWatch is a source of ideas only (`CLAWWATCH-NOTES.md`).

## Phase 0 — Stop the key leak (urgent, alone) ✅ done 2 Oct 2026

- **Owner:** chose to keep the current key for now rather than rotate it. Mitigations: the artifacts that contain it expire 14 days after creation (`retention-days: 14`, so by 15 October 2026); the now-unused `CLAUDE_API_KEY` repository secret can be deleted; a monthly spending limit in the Console is recommended.
- Remove `-PCLAUDE_API_KEY` from CI and the `BuildConfig` field.
- The app reads the key from the private file `files/api_key`, trimming whitespace and the trailing newline. If the key is missing, it shows the provisioning command. The owner provisions it from a computer:
  - macOS / Linux: `adb shell "run-as com.vcorr.claudewatch sh -c 'mkdir -p files && cat > files/api_key'" < key.txt`
  - PowerShell: `Get-Content key.txt | adb shell "run-as com.vcorr.claudewatch sh -c 'mkdir -p files && cat > files/api_key'"`
  - then delete `key.txt`.
  
  The outer double quotes matter: `adb shell` joins its arguments and re-parses them on the watch, so without them the inner quotes are lost. Debug builds allow `run-as`; there is no exported entry point, and the key never appears on a command line. The owner uses a Mac, so the macOS line applies; the README gives the full steps.
- `android:allowBackup="false"`. Never log the key, request headers, prompts or replies.

*Device checklist:* uninstall the old build (CI still signs with a random key until Phase 2a, so every install until then needs an uninstall and the key provisioned again); install; provision the key; a typed question still gets an answer.

## Phase 1 — Device probe 🟡 built 2 Oct 2026, awaiting the owner's results

A diagnostics screen in plain views, so it doesn't wait for the Compose work, in the same APK. The manifest declares `<queries>` for `android.speech.RecognitionService` and `android.intent.action.TTS_SERVICE`; without them API 30+ hides both and the probe reports false negatives. The probe requests microphone permission first, then reports:
- `SpeechRecognizer.isRecognitionAvailable`, the on-device variant, and the recognition services it can see;
- whether `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` resolves, and to which app;
- the TTS engines and voices;
- a 5-second microphone test through each speech route, with a live transcript;
- one tiny API call's round-trip time and the active network transport (Bluetooth via phone, Wi-Fi or LTE).

No public API reads the always-on display setting, so the owner reports it alongside the screenshots.

The owner runs it twice, once with the phone's Bluetooth off and the watch on Wi-Fi, and sends screenshots, noting whether always-on display is on. (Installing it needs the same uninstall and key provisioning as Phase 0.) That settles the speech route and confirms the app works over the watch's own Wi-Fi.

## Phase 2 — Foundations (signing, tests, Compose)

**Status (2 Oct 2026):** stable signing deferred; the owner prefers uninstalling between builds to putting a signing key in a public repo or using a computer. The `core` module and its unit tests were built together with Phase 3. Compose (2b) moves after Phase 3.

Two separate CI changes, so a Compose problem can't hold up the rest.

**2a. Signing and tests**
- **Stable signing:** a keystore stored as base64 CI secrets, used for debug builds of both modules. When the secrets are absent (forks, Dependabot), CI falls back to default debug signing so builds stay green. The owner keeps an offline backup of the keystore; losing it forces an uninstall, which wipes chats and the key. The first stable-signed install needs one uninstall and a fresh key provisioning.
- `versionCode` from `GITHUB_RUN_NUMBER`.
- New `core` module, plain Kotlin with no Android: request builder, reply parsing, history trimming, and later the SSE parser and sentence splitter. It applies `org.jetbrains.kotlin.jvm` *without a version*, because the root buildscript already puts KGP 2.4.20 on the classpath and a versioned plugin request fails. It applies `org.jetbrains.kotlin.plugin.serialization` at exactly 2.4.20, depends on `kotlinx-serialization-json`, and targets JVM 17. CI runs its unit tests.
- The `mobile` phone app was removed on 2 Oct 2026; the browser key page made it unnecessary.

**2b. Compose for Wear OS**
- Rebuild the question screen in Compose for Wear OS Material 3. The `org.jetbrains.kotlin.plugin.compose` plugin is pinned to 2.4.20 to match Kotlin, and library versions are pinned in the catalogue.
- Robolectric + Roborazzi screenshot tests of each screen state, with `@Config(sdk = …)` pinned to a level the chosen Robolectric version supports and a round-watch qualifier. CI uploads the PNGs.

*Device checklist:* uninstall once; install; provision the key; ask a question; install the next CI build over it and confirm the key survived.

## Phase 3 — Hands-free voice loop 🟡 built 2 Oct 2026, awaiting device check

**Order changed:** built before Compose (2b), on the existing views, because voice is what the owner is waiting for. Both speech routes ship, with automatic fallback: the in-app recogniser is tried first, and if it fails before it is ever ready (the known Galaxy Watch error is "no selected voice recognition service"), the app switches to the system dialog and remembers the choice. Google's Wear OS guide documents only the system dialog ([Voice input](https://developer.android.com/training/wearables/user-input/voice)). The probe's results now only tune the default.

- A `SpeechInput` interface with two implementations, defaulting to whichever the probe chose:
  - `SpeechRecognizer`: in-app, with a live partial transcript;
  - `RecognizerIntent`: the system dialog, relaunched automatically after each reply, with the dialog's own silence timeout acting as the follow-up window. A `RESULT_CANCELED` or empty result ends the loop and returns to IDLE.
  
  Vosk is used only if both fail.
- **State machine:** IDLE → LISTENING → THINKING → SPEAKING.
  - A tap interrupts at any point.
  - Each turn carries a token, so stale callbacks are ignored.
  - After TTS `onDone` for the final utterance, a 3.5-second follow-up window opens with a visible countdown (on the `SpeechRecognizer` route).
- **Launching the app goes straight into listening.** With the side button's double press mapped to the app, this is the Gemini-like gesture.
- Haptic tick when listening starts and stops. The microphone opens about 200 ms after speech ends, so it doesn't catch the tail of Claude's reply.
- Audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) while speaking, abandoned before listening. Interrupting calls `tts.stop()` and cancels the request.
- **Model:** Haiku 4.5 with `max_tokens` 1,024. Brevity comes from the spoken-style prompt. A reply that hits the cap is spoken up to its last full sentence. Reply *length* (never content) is logged, to check replies stay short before streaming arrives.
- In-memory conversation.

*Device checklist:* a five-turn conversation without touching the screen after launch; interrupt Claude mid-sentence; let the follow-up window lapse.

## Phase 4 — Survive the wrist dropping

Lowering the wrist to listen turns the screen off; the activity stops and the watch face returns.

**Ambient mode.** Use `AmbientLifecycleObserver`. Ambient mode only exists with always-on display enabled. Wear OS 6 apps targeting SDK 36 are treated as always-on ([Android docs](https://developer.android.com/training/wearables/views/always-on)). Samsung's default for always-on display is unverified.

**One foreground service per conversation session.**
- Type `mediaPlayback|microphone`, tied to an Ongoing Activity.
- Start it on the session's first turn, while the app is visible and `RECORD_AUDIO` is granted. Keep it running across turns, because later turns may begin with the wrist down, where starting a service is blocked on Android 12+. Starting the microphone type without the permission throws.
- Stop it when the app returns to IDLE.
- Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `FOREGROUND_SERVICE_MICROPHONE`; request `POST_NOTIFICATIONS`.

**What happens with the wrist down depends on the speech route.**
- *`SpeechRecognizer`:* the reply finishes, and follow-up listening is *attempted* inside the service. This is verified on the device, not assumed. If the recogniser refuses in the background, the app falls back to the `RecognizerIntent` behaviour below.
- *`RecognizerIntent`:* the reply finishes, then the app goes to IDLE. Android 10+ won't launch the dialog from the background, even with a foreground service. Listening resumes when the wrist comes up or the screen is tapped.

*Device checklist:* with always-on display on, and again with it off (including the return-to-watch-face timeout):
- drop the wrist mid-reply, and the reply finishes;
- with the wrist down, speak a follow-up and hear the answer (`SpeechRecognizer` route only);
- raise the wrist, and the conversation carries on.

## Phase 5 — Conversations that persist

- One JSON file per conversation, written with `AtomicFile`; reopen the latest on launch; "New chat" and "Delete all chats".
- History sent each turn is trimmed in user/assistant *pairs*, always starting with a user message.
- Scrollable transcript with rotary-crown scrolling; replies stored in full.
- **Errors:** one retry after `retry-after` on 429/529; plain messages for no network, bad key and out of credit.
- Model setting on the watch: Haiku 4.5 by default, Sonnet 5.5 as an option. For Sonnet 5.5, which by the Claude API reference rejects `thinking: {type: "disabled"}` and non-default sampling with a 400:
  - send `thinking: {type: "between_tools"}` to keep thinking off for latency;
  - send no `temperature`.
- Robust replies: `core` takes the first `text` block, not `content[0]`, and the SSE parser ignores non-text deltas, because a response may begin with a `thinking` block. Both are unit-tested.
- `stop_reason: "refusal"` gets a spoken plain-language message. Stored turns are never rewritten; trimming only drops whole oldest pairs from what is sent.
- **Decision:** keep our own HTTP client. The official `anthropic-java` SDK is not documented for Android and brings Jackson, R8 rules and size, while our client is small and fully unit-testable. This departs from Anthropic's usual "use the SDK" advice, deliberately.

*Device checklist:*
- chat, force-stop, reopen and carry on;
- start a new chat;
- scroll a long reply with the crown;
- airplane mode gives a clear message.

## Phase 6 — Faster replies

- Stream the reply (server-sent events). Each complete sentence goes to TTS with `QUEUE_ADD` and its own utterance ID. The follow-up window starts on `onDone` of the final ID.
- Interrupting mid-stream also cancels the stream and disconnects the request (audio focus and `tts.stop()` already exist from Phase 3).
- Unit tests for the SSE parser and the sentence splitter (abbreviations, decimals, "e.g.").
- Measure end-of-speech to first spoken word on the device, before and after.

*Device checklist:* first words arrive noticeably sooner than in Phase 5; interrupting mid-stream stops both the speech and the request.

## Phase 7 — Quick access

- A tile with a "Talk" button.
- README instructions for mapping the side button's double press to the app.

*Device checklist:* double press → speak → answer; Talk tile → listening.

## Phase 8 — Optional extras (each stands alone)

- Web search through Claude's server-side search tool.
- Experiment: can the app be chosen as the default assistant and launched by holding the side button?
- Bluetooth earbuds: speech output and the earbuds' microphone.
- Per-conversation token and cost display.

## Decisions log

| Decision | Choice | Why |
|---|---|---|
| Key handling | Private file via `run-as` and stdin; never in source or APK | Public repo; no exported surface |
| Speech in | `SpeechInput`: `SpeechRecognizer` or `RecognizerIntent`, chosen by the probe; Vosk last | `RecognizerIntent` is the documented Wear OS route; `SpeechRecognizer` has a known Galaxy Watch 4 failure ([flutter#130576](https://github.com/flutter/flutter/issues/130576)) |
| Model | Haiku 4.5, `max_tokens` 1,024 | Fastest and cheapest ($1 / $5 per million input / output tokens); brevity from the prompt, not the cap |
| HTTP | Own client in `core` | SDK not documented for Android; testability |
| Storage | `AtomicFile` JSON | Small data; crash-safe |
| UI | Views for now; Compose for Wear OS Material 3 after Phase 3 | Voice first; the voice screen is small, so rebuilding it later is cheap |
| Speech in, as tested | In-app `SpeechRecognizer` on Google's service (`com.google.android.tts`), in the watch's English, with live words, and a fresh recogniser for every listen; the system dialog only if that fails | The 0.4.20 trials on the Galaxy Watch 9 (Android 17): en-US, en-GB and the watch language all heard speech in-app; without live words it heard nothing; offline en-US is unavailable; there is no separate on-device recogniser |
| Current information | Anthropic's server-side web search tool (`web_search_20250305`, at most 3 searches a reply, localised by time zone only), plus the date and time in the system prompt; cited sites shown under the reply, not spoken; retried without search if the API refuses the tool | Haiku has no internet access and doesn't know the date. Web search costs $10 per 1,000 searches plus the result tokens ([docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool)) |
| Look | The "ClaudeWatch voice states" design canvas (claude.ai, private): black ground, one accent, Clawd on Idle and Thinking; built in views in 0.4, carried into Compose in 2b | One face per state of the voice loop, settled on a canvas before building |
| Phone companion | Removed (2 Oct 2026), with the watch's Data Layer listener | The browser key page replaced it, and Android's unverified-developer block made sideloading it wait 24 hours |
| Key entry | A one-page form served by the watch on the local Wi-Fi (PIN, five-attempt lockout, only while the setup screen shows); adb remains an alternative | Owner wants phone-only setup; Android's unverified-developer block (2026) makes sideloading the phone app wait 24 hours, while ADB installs to the watch are exempt ([Android FAQ](https://developer.android.com/developer-verification/guides/faq)) |
| Versioning | `ClaudeWatch-<claudewatch.version>.<CI build number>` (e.g. 0.4.41) for the artifact, the APK file, `versionName` and the diagnostics screen; `versionCode` is the build number | One name to match a download to what's on the watch; rising codes let Android treat builds as updates |
| Prompt caching | Not used | Conversations rarely pass Haiku 4.5's 4,096-token minimum |

## Owner's answers (1 October 2026)

1. Galaxy Watch 9 LTE, with no mobile plan.
2. adb from a Mac.
3. Keep the current API key for now.

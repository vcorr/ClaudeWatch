# ClaudeWatch build plan

**Goal:** a Claude voice chat on a Samsung Galaxy Watch, for carrying on a conversation when the phone and computer are out of reach. Speak, hear Claude answer, keep going. English only.

**Not goals:** replacing Gemini as the system assistant; syncing with claude.ai conversations (no public API for that); agents, health data or avatars.

## Constraints that shape the plan

- **No local Android build.** This development container cannot reach Google's Maven repository, so every build is proven only by GitHub Actions. Each phase must keep CI green, and pure logic should be unit-tested in CI (`testDebugUnitTest`) so mistakes surface before a device does.
- **Only the owner can test on the watch.** Every phase ends with a short device checklist for the owner, and nothing in the next phase may rely on an unverified device assumption from the previous one.
- **The repository is public.** Anything CI uploads is downloadable, and the source must never contain secrets.
- **The watch model is not yet known.** ClawWatch reports that Galaxy Watch 4–6 microphones struggle with speech recognition.
- **AGPL:** ClawWatch is a source of ideas only (see `CLAWWATCH-NOTES.md`).

## Phase 0 — Make it safe to ship builds (no user-visible change)

1. **Stop putting the API key in the APK.** Remove `-PCLAUDE_API_KEY` from CI and the `BuildConfig` field. The key will be stored on the device instead: interim entry via ADB (`adb shell am start` with an extra, or a debug-only intent), and later from the phone (Phase 4). The owner rotates the exposed key and deletes the old artifacts.
2. **Stable signing.** CI currently signs with a fresh debug key on every run, so each new build cannot install over the last one without uninstalling. That would wipe saved conversations and the stored key. Commit no keystore; store one as a base64 CI secret and sign debug builds with it, so updates install in place. Watch and phone must share the key and the application ID for the Wearable Data Layer to work in Phase 4.
3. **CI runs unit tests** as well as `assembleDebug`.
4. **Pick the UI toolkit once.** Move the watch UI to Compose for Wear OS with Material 3 now, while there is only one screen. That avoids building the voice UI in XML views and rewriting it later.

*Device checklist:* install the build, enter the key via ADB, confirm the existing typed question still gets an answer.

## Phase 1 — Device probe, then the voice loop

**1a. Probe (a diagnostics screen in the same APK).** It reports:
- whether `SpeechRecognizer` is available, including the on-device variant;
- the installed TTS engines and voices;
- a 5-second microphone test showing a live transcript;
- round-trip time of one tiny API call.

The owner sends a screenshot. This settles the speech-recognition route before 1b is built on it. Fallback order: `SpeechRecognizer` → `RecognizerIntent` (system dialog, so no follow-up window) → Vosk, the offline English recogniser, used only if both fail.

**1b. Voice loop** on the route the probe picked:
- one big button: tap → LISTENING (live partial transcript) → THINKING → SPEAKING;
- a tap at any point interrupts; each turn carries a token so stale callbacks are ignored;
- after a reply, a 3.5-second follow-up window with a visible countdown;
- haptic tick when listening starts and stops; screen kept on during a turn; microphone permission flow;
- uses the existing non-streaming client and Haiku 4.5, with a spoken-style system prompt (short sentences, no markdown);
- the conversation lives in memory for now.

*Device checklist:* hold a five-turn spoken conversation without touching the screen after the first tap; interrupt Claude mid-sentence; let the follow-up window lapse.

## Phase 2 — Conversations that persist

- Save each conversation to app storage (a JSON file per conversation, no database); reopen it on launch; add a "New chat" action.
- Send the whole conversation each turn, up to a budget (for example the last 40 messages). Note that Haiku 4.5's context is 200K tokens, far more than a watch conversation will use.
- Scrollable transcript with rotary-crown scrolling; replies stored in full, not truncated.
- Errors: retry once after the `retry-after` delay on 429/529/overloaded; plain-language messages for no network, bad key, or out of credit.
- **SDK decision:** try the official `anthropic-java` SDK. Keep it if it works on API 30 and adds under about 3 MB to the APK; otherwise keep our own HTTP client. Measure, don't guess.

*Device checklist:* chat, force-stop the app, reopen, carry on the same conversation; start a new one; scroll a long reply with the crown; try it in airplane mode.

## Phase 3 — Faster replies

- Stream the reply (server-sent events) and hand each complete sentence to TTS as it arrives, using `QUEUE_ADD`.
- Show the reply text growing on screen.
- Log time from end of speech to first spoken word, before and after, on the device.

*Device checklist:* compare how long the first words take with Phase 2. Interrupting mid-stream must stop both the network request and the speech.

## Phase 4 — Getting to it quickly

- **Phone companion** (the existing `mobile` module, slimmed down): enter the API key and choose the model on the phone, then sync to the watch over the Wearable Data Layer. Retire the ADB route.
- **Tile** with a "Talk" button; **launch straight into listening** (a setting), so a double press of the side button followed by speaking works like Gemini.
- Instructions in the README for mapping the double press to the app.

*Device checklist:* fresh install, key set from the phone; double press → speak → answer, in under a few seconds.

## Phase 5 — Optional extras (each stands alone)

- Web search through Claude's server-side search tool, for "what's the weather" questions.
- Experiment: can the app register as the default assistant and be launched by holding the side button?
- Wear OS 7 Live Updates so a reply keeps playing when the wrist drops.
- Bluetooth earbuds: route speech output to them, and test the earbuds' microphone.
- Spending visibility: tokens and cost per conversation.

## Decisions log

| Decision | Choice | Why |
|---|---|---|
| Speech in | System recogniser first, Vosk only as a fallback | No model to ship; answers need the network anyway |
| Model | Haiku 4.5 default, selectable later | Fastest and cheapest ($1 / $5 per million input / output tokens); latency matters most in voice |
| Storage | JSON files | One small conversation list; no need for a database |
| UI | Compose for Wear OS Material 3, from Phase 0 | Google's recommended toolkit; avoids a rewrite |
| Prompt caching | Not used | Conversations will rarely pass Haiku 4.5's 4,096-token caching minimum |

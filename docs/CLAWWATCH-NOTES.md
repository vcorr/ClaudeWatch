# What ClawWatch teaches us

Read on 1 October 2026 from [ThinkOffApp/ClawWatch](https://github.com/ThinkOffApp/ClawWatch) at commit `926fbd8` (13 Sep 2026). AGPL-3.0: these are notes on ideas. No code was copied.

Our goal is narrower than theirs: **a Claude voice chat on the watch, for when the phone and computer are out of reach.** No agent, avatars, health data or chat rooms. (Since 3 Oct 2026, by the owner's choice, Claude can read a live heart rate and today's steps when asked; still no health history.)

## How much of it is relevant

The watch app has 5,910 lines of Kotlin. They break down as follows (my classification):

| Lines | Area | Relevant to us? |
|---:|---|---|
| 373 | `VoiceEngine` — speech in (Vosk) and out (Android TTS) | **Yes**, the core |
| 187 | `SecurePrefs`, `ConfigSyncService` — encrypted key storage, phone→watch sync | **Yes** |
| 1,301 | `ClawRunner` — NullClaw agent, web search, rooms, *and* the Claude call | About 290 lines: the Claude call, memory and reply shaping |
| 1,594 | `MainActivity` — state machine, avatars, local commands | The tap/listen/speak state machine only |
| 915 | Avatars, gestures, day phases, ambient noise, nickname | No |
| 674 | Health Connect, sensors | No |
| 866 | Push alerts (326), room/state publishing (256), NullClaw curl bridge (284) | No |

Estimate: **about 14–19% is the voice-chat loop we want** (850 lines, plus perhaps 300 of `MainActivity`); the rest is the agent platform. The 290 and 300 figures are judged by reading, not counted mechanically.

Their write-up ([clawwatch-devto.md](https://github.com/ThinkOffApp/ClawWatch/blob/main/clawwatch-devto.md)) lists six obstacles. Five come from running a bundled Zig agent binary: 32-bit Samsung userland, W^X, `extractNativeLibs`, child-process network isolation, and config paths. **None of these apply to us**, because we call the API from Kotlin directly, as they eventually did too. Only the sixth applies: getting a 60-character API key onto a watch with no keyboard.

## Worth adopting (ideas)

1. **Tap-to-talk state machine:** IDLE → LISTENING → THINKING → SPEAKING. A tap at any point interrupts and returns to IDLE. Each interaction gets a token, so a stale callback from a cancelled turn can't overwrite a newer one. Taps are debounced at 300 ms.
2. **Follow-up window:** when the reply finishes speaking, the app listens again for 3.5 s, with a visible countdown. If you start talking it continues; if not, it goes idle. This is what turns Q&A into a *conversation*. This is the single most valuable idea.
3. **Live partial transcript** on screen while you speak, throttled to one update per 250 ms.
4. **Microphone setup:** `AudioSource.VOICE_RECOGNITION` with `AutomaticGainControl` and `NoiseSuppressor` when available, falling back to `MIC`. Their README says Galaxy Watch 4–6 microphones struggle and recommends the Watch 7 or later.
5. **TTS finished-speaking detection** via `UtteranceProgressListener.onDone`, not a timed delay. They also select an offline voice for the locale when one exists, and use a speech rate of 1.1.
6. **System prompt for speech:** short spoken sentences, no markdown or lists. This matters because TTS reads asterisks aloud.
7. **Conversation memory** as a rolling window of recent turns, sent with every request. Theirs is the last 10 messages, each capped at 600 characters.
8. **Key delivery without typing:** either push the key over ADB into app storage, or sync it from a phone app over the Wearable Data Layer. Their watch side listens on `/clawwatch/sync_all`; the phone-side sender is not in this repository.

## Worth doing differently

| Theirs | Ours, and why |
|---|---|
| Offline Vosk speech-to-text: 40 MB download per Vosk's model page, about 68 MB on the watch per their README, English only | Try the **watch's system speech recogniser** first: no model to ship, and probably more accurate. Offline recognition gains little when every answer needs the network anyway. Keep Vosk as a fallback if the system recogniser misbehaves on the Galaxy Watch (untested). |
| Memory lives in RAM and vanishes when the app closes | **Persist conversations** so "keep chatting" survives the app being killed, and add "new chat". |
| Long replies are cut to a few sentences, and the *cut* version is stored as history | Ask for brevity in the prompt and let the full reply scroll on screen. Store what Claude actually said. |
| Caches a ~100-token system prompt with the old `prompt-caching-2024-07-31` beta header | Pointless: Haiku 4.5 won't cache a prefix below 4,096 tokens, and even the newest models need 512 (Claude API reference). Skip caching until the conversation is long. |
| Defaults to Claude Opus 5 with replies capped at 150 tokens | Default to **Haiku 4.5** for speed in voice ($1 / $5 per million input / output tokens) and make the model selectable. |
| Waits for the whole reply, then speaks it | **Stream** the reply and speak sentence by sentence, so the first words arrive sooner. |
| Any API error just returns `null`, with no retry | Retry once on 429 / 529 (rate-limited or overloaded), and show a plain message otherwise. |
| Sets media volume to maximum before every reply, without restoring it | Respect the wearer's volume; request audio focus instead. |

## Not possible, whoever builds it

The Claude API cannot read or continue the conversations in the Claude app or on claude.ai. Watch chats will be their own history. I know of no public API for claude.ai conversations; this is from memory, not a checked source.

## Open questions to settle on the device

1. Does the system speech recogniser (`SpeechRecognizer` / `RecognizerIntent`) work on your Galaxy Watch, and how well?
2. Is the watch speaker loud enough at normal volume, or do we need ClawWatch's boost?
3. Does Haiku 4.5 feel fast enough end-to-end, or is the latency worth paying for Sonnet 5.5?

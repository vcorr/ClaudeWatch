# Research notes — October 2026

Survey taken on 1 October 2026 before refreshing the project. Sources are linked; anything not linked is marked as inference.

## Toolchain (what the refresh moved to)

| Component | Was | Now | Source |
|---|---|---|---|
| Gradle | 8.7 | 9.8.0 (released 29 Sep 2026) | [Gradle release notes](https://docs.gradle.org/current/release-notes.html) |
| Android Gradle plugin | 8.5.2 | 9.4.1 (needs Gradle ≥ 9.6.0, JDK ≥ 17; max API 37) | [AGP 9.4 notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes), Google Maven metadata |
| Kotlin | 1.9.25 via `kotlin-android` plugin | 2.4.20 via AGP's built-in Kotlin | [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin), [AGP 9.0 notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin) |
| kotlinx-coroutines | 1.7.3 | 1.11.0 | Maven Central metadata |
| androidx.wear:wear | 1.3.0 | 1.4.0 (25 Feb 2026) | [Wear release notes](https://developer.android.com/jetpack/androidx/releases/wear) |
| androidx.appcompat | 1.7.0 | removed (unused) | [AppCompat release notes](https://developer.android.com/jetpack/androidx/releases/appcompat) |
| compileSdk / targetSdk | 35 / 34 | 36 / 36 | see Play requirement below |

**Google Play target API rule** (from 31 Aug 2026): new apps and updates must target API 36, *except* Wear OS, which must target API 35 or higher. ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/11926878))

API 37 (Android 17) was not chosen: the SDK platform release notes did not confirm a stable API 37 platform when checked.

## Platform direction

- **Wear OS 7** (announced May 2026): Live Updates replace the Ongoing Activities API; Gemini Intelligence is promised on select watches later in 2026. ([Android Developers Blog](https://developer.android.com/blog/posts/what-s-new-in-wear-os-7), [Google blog](https://blog.google/products-and-platforms/platforms/wear-os/google-io-2026-wear-os/))
- **Compose for Wear OS Material 3** has been stable since `androidx.wear.compose` 1.5.0 (Aug 2025) and is Google's recommended UI toolkit. The current app still uses XML views. ([Wear Compose release notes](https://developer.android.com/jetpack/androidx/releases/wear-compose))
- **Gemini** is the default assistant on the Galaxy Watch8 with One UI 8. ([Samsung](https://www.samsung.com/us/support/answer/ANS10003405/))
- **Launching our app from the button:** the Galaxy Watch Home key's *double press* can open any app. ([Samsung](https://www.samsung.com/us/support/answer/ANS10003380/)) *Press and hold* appears limited to Bixby or the assistant. Wear OS does have a "default assistant app" setting, but whether a third-party app can register for it and be launched by the button is **unverified**. (Inference from user reports.)

## Claude API

- Lineup on 1 Oct 2026: Fable 5.1, Opus 5.5, Sonnet 5.5, Haiku 4.5. Haiku 4.5 remains the fastest and cheapest at $1 / $5 per million input / output tokens. ([Models overview](https://docs.anthropic.com/en/docs/about-claude/models/overview), [Pricing](https://docs.anthropic.com/en/docs/about-claude/pricing))
- An official Java SDK exists (`com.anthropic:anthropic-java`, 2.68.0 on Maven Central). Our hand-rolled `HttpURLConnection` client is smaller, which matters on a watch. Whether the SDK's size is acceptable is untested. (Inference.)

## Similar projects

| Project | What it does | Licence | Activity | Worth borrowing |
|---|---|---|---|---|
| [ThinkOffApp/ClawWatch](https://github.com/ThinkOffApp/ClawWatch) | Voice agent on Galaxy Watch: Vosk offline speech-to-text, Claude, Android TTS | AGPL-3.0 | 64★, updated Sep 2026 | Local routing for timers and pulse before calling the model. Forces media volume up for TTS. API key pushed over ADB, not compiled in. Recommends Watch 7+ for microphone quality. |
| [Hiburger/Wearstral](https://github.com/Hiburger/Wearstral) | Mistral chat client for Wear OS (Compose) | GPL-3.0 | New, Sep 2026 | API key in DataStore. Its roadmap aims at being selectable as the main assistant, worth watching. |
| [abheesh-03/wearable-ai-companion](https://github.com/abheesh-03/wearable-ai-companion) | Wear OS + Claude via a FastAPI backend | none stated | Sep 2026 | Keeps the API key off the watch entirely. On-device intent routing. |
| [petepiet/iDictate](https://github.com/petepiet/iDictate) | Phone + Wear OS dictation; Whisper speech-to-text, Claude analysis | GPL-3.0 | 9★, Jul 2026 | Phone/watch split. |
| [DevEmperor/WristAssist](https://github.com/DevEmperor/WristAssist) | ChatGPT and DALL·E on Wear OS | Apache-2.0 | 124★, **archived** | The most-starred precedent; read its open issues for pitfalls. |
| [simplifylabs/WearAI](https://github.com/simplifylabs/WearAI) | ChatGPT with voice in and out (Compose) | Apache-2.0 | 15★, 2023 | Small Compose reference. |
| WristAnswers (Play Store) | Commercial AI chat for Wear OS | proprietary | 1,947 ratings, 3.8★ | Answer-length setting, TTS speed, favourites. |

A separate cluster uses the watch to **remote-control Claude Code** rather than chat. Examples: [WristPilot](https://github.com/kayomarz97/wristpilot), [ccwearos](https://github.com/caamanoluismiguel/ccwearos), [agent-watch](https://github.com/gabrielmarcano/agent-watch), [claude-master-watch](https://github.com/frsorrentino/claude-master-watch). That is a different product.

**Licence caution:** ClawWatch (AGPL), Wearstral and iDictate (GPL) are copyleft. Borrow ideas, not code, unless this project adopts a compatible licence. This repository currently has no licence.

## Open questions

1. Can a third-party app be chosen as the default assistant on a Galaxy Watch and be launched by holding the button? This can only be tested on the device.
2. System speech recognition (`RecognizerIntent`) or offline Vosk? Vosk adds about 68 MB (ClawWatch's figure) but works without a connection.
3. Where should the API key live? *Answered:* in a private file on the watch, entered once from the phone's browser through a PIN-protected page the watch serves on the local Wi-Fi (or pushed over adb); never compiled in.

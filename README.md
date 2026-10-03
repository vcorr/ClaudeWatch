# ClaudeWatch

A Claude voice chat for Wear OS, built for a Samsung Galaxy Watch. Work in progress: see [`docs/PLAN.md`](docs/PLAN.md).

The app needs your own Anthropic API key. The key is **never** built into the app; you give it to the watch once, from the phone's browser (or over adb from a computer).

## Install from the phone (no computer)

1. **Watch:** Settings → About watch → Software information, tap **Software version** five times. Then **Settings → Developer options**: turn on **ADB debugging** and **Wireless debugging**. Put the watch on the same Wi-Fi as the phone.
2. **Phone:** in the browser, signed in to GitHub, open the latest successful run on the [Actions page](https://github.com/vcorr/ClaudeWatch/actions), download the `ClaudeWatch-0.4.N` file listed under *Artifacts* (N is the build number) and unzip it with My Files.
3. Install a phone ADB app such as **Bugjaeger** or **Wear Installer 2**. Connect it to the watch's IP address (shown under Wireless debugging), pair with the code the watch shows, tap *Always allow* on the watch, and install `ClaudeWatch-0.4.N.apk` *through that app*. Tapping the APK in My Files won't work: it is a watch app, so the phone refuses it. Installs over ADB are exempt from Google's unverified-developer block, so there's no waiting period.
4. **Key:** open ClaudeWatch on the watch. With no key set, it shows an address such as `http://192.168.1.23:8080` and a six-digit PIN. Open that address in the phone's browser, paste the API key and the PIN, and tap **Save to watch**. The watch confirms, the page says you can close it, and the watch stops serving it.
5. Opening the app starts listening, over your watch face: speak, and Claude answers out loud as the reply streams in, then listens for a follow-up while a ring around the microphone runs down. While it listens, **tap to send** what it heard or **hold to cancel**; while it talks, **tap anywhere to stop** it. Said nothing? It closes itself after a few seconds, as does a conversation that has ended; touch the screen to keep it open. Reopened within 30 minutes, it carries on the same chat; **New** starts afresh. **Long-press the microphone** for the build version and voice diagnostics.
6. **Quick start:** on the watch, Settings → Advanced features → Customise buttons → Double press → ClaudeWatch. A double press of the Home key then opens it listening. Or add the **ClaudeWatch** tile (long-press the watch face's tiles, then +) and tap **Talk**.
7. **Language:** conversations are in Finnish when the watch can both hear and speak Finnish, otherwise in English, decided afresh each time the app starts. If it falls back to English, check that a Finnish voice is installed (Settings → General → Text-to-speech, or Google's Speech Recognition and Synthesis). The diagnostics list each engine's English and Finnish voices (stored, online, to download), and Test speaking shows which voice the app chose and why the conversation is in the language it is.
8. On first use the watch asks for permissions: the microphone, plus location, calendar, heart rate and physical activity for Claude's watch tools (weather, where you are, your calendar, your pulse and steps today, the battery and the next alarm). Each is asked for once and read only when a question needs it. Web search, which is paid per search, runs only when you ask Claude to look something up.

The key page exists only while the watch shows it, needs the PIN, and stops after five wrong PINs. It travels over your local Wi-Fi unencrypted, so use it on your home network, not on public Wi-Fi. If Claude ever rejects the key, the watch shows the page again.

If an update fails to install with a signature error, uninstall the old version first (on the watch, or `adb uninstall com.vcorr.claudewatch`) and set the key again. Until builds use a fixed signing key, every new build needs this.

## Install from a Mac

1. `brew install android-platform-tools`
2. Enable developer options, ADB debugging and Wireless debugging on the watch as above.
3. Under **Wireless debugging → Pair new device**:
   ```sh
   adb pair <watch-ip>:<pairing-port>      # enter the pairing code
   adb connect <watch-ip>:<debug-port>     # the port shown on the Wireless debugging screen
   adb install -r ClaudeWatch-0.4.N.apk
   ```
4. Or set the key from the Mac instead of the browser page. Copy the key so it is on the clipboard, then:
   ```sh
   pbpaste > key.txt
   adb shell "run-as com.vcorr.claudewatch sh -c 'mkdir -p files && cat > files/api_key'" < key.txt
   rm key.txt
   ```
   The outer double quotes matter, because `adb shell` re-parses its arguments on the watch.

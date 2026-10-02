# ClaudeWatch

A Claude voice chat for Wear OS, built for a Samsung Galaxy Watch. Work in progress: see [`docs/PLAN.md`](docs/PLAN.md).

The app needs your own Anthropic API key. The key is **never** built into the app; you send it to the watch once, from the phone app (or over adb from a computer).

## Install from the phone (no computer)

Both APKs must come from **the same Actions run**: the phone app can only talk to the watch app when both are signed with the same key, and each run currently signs with its own.

1. On the watch: **Settings → About watch → Software information**, tap **Software version** five times. Then **Settings → Developer options**: turn on **ADB debugging** and **Wireless debugging**. Keep the watch on the same Wi-Fi as the phone.
2. On the phone, in the browser and signed in to GitHub, open the latest successful run on the [Actions page](../../actions). Download **both** `wear-debug-apk` and `mobile-debug-apk`, and unzip them with My Files.
3. **Watch app:** install a phone ADB app such as Wear Installer 2 or Bugjaeger. Connect it to the watch's IP address (shown under Wireless debugging), pair with the code the watch shows, and tap *Always allow* on the watch. Then install `app-debug.apk` *through that app*. Tapping it in My Files tries to install it on the phone, which refuses because it is a watch app.
4. **Phone app:** tap `mobile-debug.apk` in My Files. If Play Protect blocks it, choose *More details → Install anyway*.
5. Open ClaudeWatch on the phone, paste your API key, and tap **Send key to watch**. Wait for "The watch saved the key."
6. On the watch, open ClaudeWatch and ask something.

If an update ever fails to install with a signature error, uninstall the old version first. Until builds use a fixed signing key, each new build needs this, and the key must then be sent again.

## Install from a Mac

1. `brew install android-platform-tools`
2. Enable developer options, ADB debugging and Wireless debugging on the watch as above.
3. Under **Wireless debugging → Pair new device**:
   ```sh
   adb pair <watch-ip>:<pairing-port>      # enter the pairing code
   adb connect <watch-ip>:<debug-port>     # the port shown on the Wireless debugging screen
   adb install -r app-debug.apk
   ```
4. Set the key without the phone app. Copy the key so it is on the clipboard, then:
   ```sh
   pbpaste > key.txt
   adb shell "run-as com.vcorr.claudewatch sh -c 'mkdir -p files && cat > files/api_key'" < key.txt
   rm key.txt
   ```
   The outer double quotes matter, because `adb shell` re-parses its arguments on the watch.

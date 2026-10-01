# ClaudeWatch

A Claude voice chat for Wear OS, built for a Samsung Galaxy Watch. Work in progress: see [`docs/PLAN.md`](docs/PLAN.md).

The app needs your own Anthropic API key. The key is **never** built into the app; you put it on the watch once, over adb.

## Install on the watch (macOS)

1. Install adb: `brew install android-platform-tools`.
2. On the watch, enable developer options: **Settings → About watch → Software information**, tap **Software version** five times.
3. **Settings → Developer options:** turn on **ADB debugging** and **Wireless debugging**. The watch must be on the same Wi-Fi network as the Mac.
4. Under **Wireless debugging → Pair new device**, note the pairing code and address, then on the Mac:
   ```sh
   adb pair <watch-ip>:<pairing-port>      # enter the pairing code
   adb connect <watch-ip>:<debug-port>     # the port shown on the Wireless debugging screen
   ```
5. Download `wear-debug-apk` from the latest successful run on the [Actions page](../../actions), unzip it, and install:
   ```sh
   adb install -r app-debug.apk
   ```
   If the install fails with a signature error, run `adb uninstall com.vcorr.claudewatch` first. Until builds use a fixed signing key, every new build needs this, and the key must then be set again.

## Set the API key

Copy the key from the [Anthropic Console](https://console.anthropic.com/) so it is on the clipboard, then:

```sh
pbpaste > key.txt
adb shell "run-as com.vcorr.claudewatch sh -c 'mkdir -p files && cat > files/api_key'" < key.txt
rm key.txt
```

Using the clipboard keeps the key out of your shell history. The outer double quotes matter, because `adb shell` re-parses its arguments on the watch.

The same command works for the phone test app (`mobile`) with the phone connected instead.

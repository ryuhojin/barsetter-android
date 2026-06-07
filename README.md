# Barsetter Android

Android-only local menu viewer for Barsetter.

This app does not open `barsetter-client.pages.dev` in a WebView. It embeds the
static `Barsetter-client` build output in the APK and downloads only menu JSON
data during operation.

## Layout

```txt
app/src/main/assets/www/      Embedded Barsetter-client build output
app/src/main/java/...         Thin Kotlin WebView wrapper
scripts/sync-client.sh        Build and copy Barsetter-client into Android assets
scripts/build-debug.sh        Build a debug APK with a local Gradle distribution
```

## Requirements

- JDK 17
- Android SDK with API 35 installed
- `ANDROID_HOME` or `ANDROID_SDK_ROOT` pointing to the Android SDK

The app targets Android 5.0+ (`minSdk 21`). Very old devices still depend on the
installed Android System WebView version. If a device shows a blank page, update
Android System WebView/Chrome first.

## Build

```bash
./scripts/sync-client.sh
./scripts/build-debug.sh
```

The debug APK is written under:

```txt
app/build/outputs/apk/debug/app-debug.apk
```

## Runtime

The WebView loads:

```txt
https://barsetter.local/index.html?source=local
```

Requests are intercepted inside the app:

- `/index.html` and `/assets/*` are served from `assets/www`.
- `/local/menu.json` is served from app internal storage.
- If no downloaded menu exists yet, the app falls back to the bundled
  `assets/www/json/baro.json` sample.

Use the source URL field at the top of the app to download a menu JSON file. The
default source is:

```txt
https://barsetter-client.pages.dev/json/baro.json
```

Later, this URL should be replaced by a Barsetter admin/device-token endpoint.

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

For service sideloading, build the release-signed APK:

```bash
./scripts/sync-client.sh
./scripts/build-release.sh
```

The release APK is written as:

```txt
barsetter.apk
```

The first release build creates a local signing key under `.signing/`. Keep that
directory backed up because future APK updates must use the same signing key.

## Runtime

The WebView loads:

```txt
https://barsetter.local/index.html?source=local
```

Requests are intercepted inside the app:

- `/index.html` and `/assets/*` are served from `assets/www`.
- `/fonts/*` is served from `assets/www` so the embedded WebView uses the same
  bundled menu font as the web version.
- `/local/menu.json` is served from app internal storage.
- If no downloaded menu exists yet, the app falls back to the bundled
  `assets/www/json/baro.json` sample.

On first launch, the app asks for an encoded bar code such as `YmFybw`. The
code decodes to the menu slug (`baro`) and the app downloads that bar's JSON.
After the menu JSON is stored, later launches skip the setup screen and open the
full-screen WebView menu directly. Long-press the bar title inside the menu to
refresh the stored menu for the selected bar.

Menu JSON is downloaded from:

```txt
https://barsetter-client.pages.dev/json/{slug}.json
```

Later, this URL should be replaced by a Barsetter admin/device-token endpoint.

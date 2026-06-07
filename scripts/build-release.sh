#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_VERSION="${GRADLE_VERSION:-8.7}"
GRADLE_HOME="$ROOT_DIR/.gradle-local/gradle-$GRADLE_VERSION"
GRADLE_ZIP="$ROOT_DIR/.gradle-local/gradle-$GRADLE_VERSION-bin.zip"
SIGNING_DIR="$ROOT_DIR/.signing"
SIGNING_FILE="$SIGNING_DIR/release.properties"
KEYSTORE_FILE="$SIGNING_DIR/barsetter-release.jks"
OUTPUT_APK="$ROOT_DIR/barsetter.apk"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
JDK_HOME="${JAVA_HOME:-}"

if [[ -z "$SDK_DIR" && -d "$HOME/Library/Android/sdk" ]]; then
  SDK_DIR="$HOME/Library/Android/sdk"
fi

if [[ -z "$SDK_DIR" ]]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT must point to an Android SDK." >&2
  exit 1
fi

export ANDROID_HOME="$SDK_DIR"

if [[ -z "$JDK_HOME" ]]; then
  for candidate in \
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [[ -x "$candidate/bin/java" ]]; then
      JDK_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "$JDK_HOME" || ! -x "$JDK_HOME/bin/java" ]]; then
  echo "JAVA_HOME must point to JDK 17." >&2
  exit 1
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

random_password() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 30 | tr -d '=+/' | cut -c 1-24
  else
    uuidgen | tr -d '-' | cut -c 1-24
  fi
}

if [[ ! -f "$SIGNING_FILE" ]]; then
  mkdir -p "$SIGNING_DIR"
  chmod 700 "$SIGNING_DIR"
  STORE_PASSWORD="$(random_password)"
  cat > "$SIGNING_FILE" <<PROPERTIES
storeFile=$KEYSTORE_FILE
storePassword=$STORE_PASSWORD
keyAlias=barsetter
keyPassword=$STORE_PASSWORD
PROPERTIES
  chmod 600 "$SIGNING_FILE"
fi

read_signing_property() {
  awk -F= -v key="$1" '$1 == key { sub($1 "=",""); print; exit }' "$SIGNING_FILE"
}

storeFile="$(read_signing_property storeFile)"
storePassword="$(read_signing_property storePassword)"
keyAlias="$(read_signing_property keyAlias)"
keyPassword="$(read_signing_property keyPassword)"

if [[ "$keyPassword" != "$storePassword" ]]; then
  tmpFile="$(mktemp)"
  awk -F= -v password="$storePassword" 'BEGIN { OFS = FS } $1 == "keyPassword" { $2 = password } { print }' "$SIGNING_FILE" > "$tmpFile"
  mv "$tmpFile" "$SIGNING_FILE"
  chmod 600 "$SIGNING_FILE"
  keyPassword="$storePassword"
fi

if [[ ! -f "$KEYSTORE_FILE" ]]; then
  keytool -genkeypair \
    -keystore "$storeFile" \
    -storepass "$storePassword" \
    -keypass "$keyPassword" \
    -alias "$keyAlias" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=barsetter,O=barsetter,C=KR"
fi
chmod 600 "$storeFile"

if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  mkdir -p "$ROOT_DIR/.gradle-local"
  if [[ ! -f "$GRADLE_ZIP" ]]; then
    curl -L "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$GRADLE_ZIP"
  fi
  unzip -q "$GRADLE_ZIP" -d "$ROOT_DIR/.gradle-local"
fi

cd "$ROOT_DIR"
"$GRADLE_HOME/bin/gradle" assembleRelease
cp "$ROOT_DIR/app/build/outputs/apk/release/app-release.apk" "$OUTPUT_APK"
echo "Wrote $OUTPUT_APK"

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_VERSION="${GRADLE_VERSION:-8.7}"
GRADLE_HOME="$ROOT_DIR/.gradle-local/gradle-$GRADLE_VERSION"
GRADLE_ZIP="$ROOT_DIR/.gradle-local/gradle-$GRADLE_VERSION-bin.zip"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
JDK_HOME="${JAVA_HOME:-}"

if [[ -z "$SDK_DIR" && -d "$HOME/Library/Android/sdk" ]]; then
  SDK_DIR="$HOME/Library/Android/sdk"
fi

if [[ -z "$SDK_DIR" ]]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT must point to an Android SDK." >&2
  echo "Example: export ANDROID_HOME=/Users/ryuhojin/Library/Android/sdk" >&2
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

if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  mkdir -p "$ROOT_DIR/.gradle-local"
  if [[ ! -f "$GRADLE_ZIP" ]]; then
    curl -L "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$GRADLE_ZIP"
  fi
  unzip -q "$GRADLE_ZIP" -d "$ROOT_DIR/.gradle-local"
fi

cd "$ROOT_DIR"
"$GRADLE_HOME/bin/gradle" assembleDebug

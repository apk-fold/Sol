#!/usr/bin/env sh
# Lightweight wrapper for repositories created without the binary Gradle wrapper JAR.
set -eu
GRADLE_VERSION="8.13"
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/foldbook-distributions/gradle-${GRADLE_VERSION}"
if [ ! -x "$GRADLE_HOME_DIR/bin/gradle" ]; then
  mkdir -p "$(dirname "$GRADLE_HOME_DIR")"
  TMP_ZIP="${TMPDIR:-/tmp}/gradle-${GRADLE_VERSION}-$$.zip"
  curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$TMP_ZIP"
  unzip -q "$TMP_ZIP" -d "$(dirname "$GRADLE_HOME_DIR")"
  rm -f "$TMP_ZIP"
fi
exec "$GRADLE_HOME_DIR/bin/gradle" "$@"

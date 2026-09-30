#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.9"
BASE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/aiway-gradle"
DIST_DIR="$BASE_DIR/gradle-$GRADLE_VERSION"
ZIP_FILE="$BASE_DIR/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_BIN="$DIST_DIR/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$BASE_DIR"
  if [ ! -f "$ZIP_FILE" ]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    curl -fL --retry 3 --retry-delay 2 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP_FILE"
  fi
  rm -rf "$DIST_DIR"
  unzip -q "$ZIP_FILE" -d "$BASE_DIR"
fi
exec "$GRADLE_BIN" "$@"

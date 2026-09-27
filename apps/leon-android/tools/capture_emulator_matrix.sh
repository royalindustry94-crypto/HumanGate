#!/usr/bin/env bash
set -euo pipefail

OUT="apps/leon-android/app/build/reports/leon-preview"
APK="apps/leon-android/app/build/outputs/apk/debug/app-debug.apk"

mkdir -p "$OUT"
adb install -r "$APK"

capture_state() {
  local name="$1"
  local pose="$2"
  local action="${3:-}"

  adb shell am force-stop ai.leon.companion
  if [[ -n "$action" ]]; then
    adb shell am start -W -n ai.leon.companion/.MainActivity \
      --ez ai.leon.companion.VISUAL_HOST true \
      --es ai.leon.companion.TEST_POSE "$pose" \
      --es ai.leon.companion.TEST_ACTION "$action" \
      --ez ai.leon.companion.DISABLE_RANDOM true
  else
    adb shell am start -W -n ai.leon.companion/.MainActivity \
      --ez ai.leon.companion.VISUAL_HOST true \
      --es ai.leon.companion.TEST_POSE "$pose" \
      --ez ai.leon.companion.DISABLE_RANDOM true
  fi

  sleep 3
  adb exec-out screencap -p > "$OUT/$name.png"
}

capture_state emulator-idle idle
capture_state emulator-thinking thinking
capture_state emulator-speaking speaking
capture_state emulator-chin idle chin
capture_state emulator-head-left idle head-left

adb shell am force-stop ai.leon.companion
adb shell appops set ai.leon.companion SYSTEM_ALERT_WINDOW allow
adb shell am start -W -n ai.leon.companion/.MainActivity \
  --ez ai.leon.companion.OVERLAY_HOST true \
  --ez ai.leon.companion.START_TEST_OVERLAY true
sleep 3
adb exec-out screencap -p > "$OUT/emulator-overlay.png"

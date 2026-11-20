#!/usr/bin/env bash
# NEXUS on a real Android system: install the debug APK on the CI emulator,
# run the owner-journey instrumentation test against Ollama on the host,
# then launch the app as a person would and capture screenshots and a video.
# Everything lands in build/emulator-smoke for the workflow to upload.
set -uo pipefail

PKG=ai.hybrid.commandcenter
OUT=build/emulator-smoke
MODEL="${NEXUS_OLLAMA_MODEL:-qwen2.5:0.5b}"
mkdir -p "$OUT/shots"

adb wait-for-device
# software-rendered CI emulators often show "Pixel Launcher isn't responding";
# hide system error dialogs so they do not cover the app in screenshots
adb shell settings put global hide_error_dialogs 1 || true
adb shell getprop ro.product.cpu.abilist | tee "$OUT/abilist.txt"
adb install -r -t app/build/outputs/apk/debug/app-debug.apk 2>&1 | tee "$OUT/install.txt"
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk 2>&1 | tee -a "$OUT/install.txt"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
adb logcat -c || true

echo "== instrumentation"
adb shell am instrument -w \
  -e class "$PKG.NexusSmokeTest" \
  -e ollamaUrl "http://10.0.2.2:11434" \
  -e ollamaModel "$MODEL" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/instrument.txt"

echo "== collect test screenshots"
for f in $(adb shell run-as "$PKG" ls files/smoke 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as "$PKG" cat "files/smoke/$f" > "$OUT/shots/$f"
done
ls -la "$OUT/shots" || true

echo "== live launch (real animations, real rendering)"
adb shell am force-stop "$PKG" || true
adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/launch.txt"
sleep 10
adb exec-out screencap -p > "$OUT/shots/20_live_chat.png" || true

# open a new chat so the big hologram greets on the bridge
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
if [ -f "$OUT/ui.xml" ]; then
  python3 - "$OUT/ui.xml" > "$OUT/tap.txt" <<'PY' || true
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'content-desc="Новый чат"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
PY
  if [ -s "$OUT/tap.txt" ]; then
    adb shell input tap $(cat "$OUT/tap.txt")
    sleep 6
  fi
fi
adb exec-out screencap -p > "$OUT/shots/21_live_home.png" || true
adb shell screenrecord --time-limit 12 --bit-rate 6000000 /sdcard/nexus-live.mp4 || true
adb pull /sdcard/nexus-live.mp4 "$OUT/nexus-live.mp4" >/dev/null 2>&1 || true

adb logcat -d > "$OUT/logcat.txt" || true
grep -E "FATAL EXCEPTION" -A 25 "$OUT/logcat.txt" > "$OUT/crashes.txt" || true

if grep -q "FAILURES!!!" "$OUT/instrument.txt" || ! grep -q "^OK (" "$OUT/instrument.txt"; then
  echo "Instrumentation failed — see instrument.txt and logcat.txt"
  exit 1
fi
if grep -q "Process: $PKG" "$OUT/crashes.txt"; then
  echo "The app crashed during the live launch — see crashes.txt"
  exit 1
fi
echo "NEXUS emulator smoke: OK"

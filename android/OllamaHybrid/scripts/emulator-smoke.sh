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

echo "== models that are already on the phone (the «found» journey)"
FOUND=false
mkdir -p /tmp/nexus-found
fetch() {
  curl -fsSL --retry 3 -o "/tmp/nexus-found/$2" "$1" && adb push "/tmp/nexus-found/$2" "/sdcard/Download/$2" >/dev/null
}
if fetch "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/Qwen3.5-0.8B-Q4_K_M.gguf" Qwen3.5-0.8B-Q4_K_M.gguf &&
   fetch "https://huggingface.co/ggml-org/gemma-3-270m-it-GGUF/resolve/e7647be17ae1108f2f605ed061ca0608b171afff/gemma-3-270m-it-Q8_0.gguf" gemma-3-270m-it-Q8_0.gguf; then
  FOUND=true
fi
# «Доступ ко всем файлам», as the owner would allow it in Settings
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
adb shell ls -la /sdcard/Download | tee "$OUT/download-dir.txt"
adb logcat -c || true

echo "== instrumentation"
adb shell am instrument -w \
  -e class "$PKG.NexusSmokeTest" \
  -e notClass "$PKG.NexusSmokeTest#e_meshJourney" \
  -e ollamaUrl "http://10.0.2.2:11434" \
  -e ollamaModel "$MODEL" \
  -e gemma "${NEXUS_GEMMA:-false}" \
  -e found "$FOUND" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/instrument.txt"

echo "== NEXUS Mesh: this emulator + a second node on the CI host"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell run-as "$PKG" rm -f files/mesh-link.txt files/mesh-done.txt || true
adb shell am instrument -w -e class "$PKG.NexusSmokeTest#e_meshJourney" -e mesh true \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/mesh-instrument.txt" 2>&1 &
MESH_PID=$!
LINK=""
for i in $(seq 1 120); do
  LINK=$(adb exec-out run-as "$PKG" cat files/mesh-link.txt 2>/dev/null | tr -d '\r')
  [ -n "$LINK" ] && break
  sleep 5
done
if [ -n "$LINK" ]; then
  adb forward tcp:47811 tcp:47811
  MESH_LINK="$LINK" MESH_TARGET="127.0.0.1:47811" MESH_OUT="$PWD/$OUT/mesh-result.txt" \
    gradle --no-daemon -q -Pnexus.emulatorAbi=true :app:testDebugUnitTest --tests "ai.hybrid.commandcenter.mesh.MeshHostPeerE2E" \
    > "$OUT/mesh-host.txt" 2>&1 || echo "HOST PEER FAILED" >> "$OUT/mesh-host.txt"
else
  echo "the phone never published an invite" > "$OUT/mesh-host.txt"
fi
wait $MESH_PID
cat "$OUT/mesh-result.txt" 2>/dev/null || true

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
if grep -q "FAILURES!!!" "$OUT/mesh-instrument.txt" || ! grep -q "^OK (" "$OUT/mesh-instrument.txt" || grep -q "HOST PEER FAILED" "$OUT/mesh-host.txt"; then
  echo "NEXUS Mesh journey failed — see mesh-instrument.txt, mesh-host.txt and mesh-result.txt"
  exit 1
fi
if grep -q "Process: $PKG" "$OUT/crashes.txt"; then
  echo "The app crashed during the live launch — see crashes.txt"
  exit 1
fi
echo "NEXUS emulator smoke: OK"

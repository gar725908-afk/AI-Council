#!/usr/bin/env bash
# NEXUS llama.cpp engine under AddressSanitizer on the CI emulator: the
# load / answer / cancel / free lifecycle tests must pass with no sanitizer
# report, and a planted use-after-free must be reported (proof the sanitizer
# is really watching). Results land in build/asan-lifecycle.
set -uo pipefail

PKG=ai.hybrid.commandcenter
OUT=build/asan-lifecycle
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
MODEL=/sdcard/Download/gemma-3-270m-it-Q8_0.gguf
mkdir -p "$OUT"

adb wait-for-device
adb shell settings put global hide_error_dialogs 1 || true
adb install -r -t app/build/outputs/apk/debug/app-debug.apk 2>&1 | tee "$OUT/install.txt"
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk 2>&1 | tee -a "$OUT/install.txt"
curl -fsSL --retry 3 -o /tmp/gemma-3-270m-it-Q8_0.gguf \
  "https://huggingface.co/ggml-org/gemma-3-270m-it-GGUF/resolve/e7647be17ae1108f2f605ed061ca0608b171afff/gemma-3-270m-it-Q8_0.gguf"
adb push /tmp/gemma-3-270m-it-Q8_0.gguf "$MODEL" >/dev/null
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
# what Android extracted: wrap.sh must sit next to the sanitizer runtime and be executable
APK_DIR=$(adb shell pm path "$PKG" | head -1 | tr -d '\r' | sed 's/^package://; s#/base.apk$##')
adb shell ls -la "$APK_DIR/lib/x86_64/" 2>&1 | tee "$OUT/native-libs.txt" || true

echo "== lifecycle under AddressSanitizer"
adb logcat -c || true
timeout 2400 adb shell am instrument -w -e class "$PKG.nexus.NativeLifecycleTest" \
  -e model "$MODEL" -e asan true "$RUNNER" 2>&1 | tee "$OUT/instrument.txt"
adb logcat -d > "$OUT/logcat.txt" || true

echo "== planted use-after-free must be caught"
adb logcat -c || true
timeout 600 adb shell am instrument -w -e class "$PKG.nexus.NativeLifecycleTest#asanCanary" \
  -e model "$MODEL" -e asanCanary true "$RUNNER" > "$OUT/canary-instrument.txt" 2>&1
sleep 3
adb logcat -d > "$OUT/canary-logcat.txt" || true
grep -n "AddressSanitizer" "$OUT/canary-logcat.txt" | head -20 | tee "$OUT/canary-report.txt"

STATUS=0
if grep -q "ERROR: AddressSanitizer" "$OUT/logcat.txt"; then
  echo "AddressSanitizer reported a memory error in the lifecycle run:"
  grep -n -A 30 "ERROR: AddressSanitizer" "$OUT/logcat.txt" | head -80
  STATUS=1
fi
if grep -q "FAILURES!!!" "$OUT/instrument.txt" || ! grep -q "^OK (" "$OUT/instrument.txt"; then
  echo "Lifecycle tests failed under AddressSanitizer — see instrument.txt and logcat.txt"
  STATUS=1
fi
if ! grep -q "heap-use-after-free" "$OUT/canary-logcat.txt"; then
  echo "The planted use-after-free was NOT reported: the sanitizer is not active"
  STATUS=1
fi
[ "$STATUS" = 0 ] && echo "NEXUS ASan lifecycle: OK (clean run, canary caught)"
exit $STATUS

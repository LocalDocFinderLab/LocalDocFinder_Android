#!/usr/bin/env bash
# Smoke test for a release (R8-shrunk) APK on an emulator: installs it, drops three sample files into Download,
# launches the app (its first-launch crawl indexes them) and checks in logcat that
#   1. PDFBox extracted the text PDF,
#   2. OCR read the scanned (image-only) PDF,
#   3. OCR read the photo of a receipt,
# and that the app never crashed. Shrinking bugs (removed classes, broken reflection) show up as crashes or as
# missing markers here.
set -u
APK="$1"
PKG=com.aistudio.vectorsearch.dvmxqe
DIR="$(cd "$(dirname "$0")/.." && pwd)/smoke"

adb wait-for-device
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb shell mkdir -p /sdcard/Download
adb push "$DIR/smoke-text.pdf" "$DIR/smoke-scanned.pdf" "$DIR/smoke-receipt.jpg" /sdcard/Download/ >/dev/null
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null

MARKERS=(
  "PDFBox extracted [0-9]+/[0-9]+ page"
  "Read [0-9]+ scanned page\(s\)"
  "Recognized [0-9]+ characters in"
)
found_all=0
for _ in $(seq 1 60); do
  sleep 5
  log="$(adb logcat -d)"
  if echo "$log" | grep -q "Process: $PKG"; then
    echo "::error::The app crashed"
    echo "$log" | grep -B2 -A60 "FATAL EXCEPTION"
    exit 1
  fi
  ok=1
  for m in "${MARKERS[@]}"; do echo "$log" | grep -Eq "$m" || ok=0; done
  if [ "$ok" = 1 ]; then found_all=1; break; fi
done

log="$(adb logcat -d)"
for m in "${MARKERS[@]}"; do
  if echo "$log" | grep -Eq "$m"; then echo "OK      $m"; else echo "MISSING $m"; fi
done
if ! adb shell pidof "$PKG" >/dev/null; then echo "::error::The app is not running"; found_all=0; fi
if [ "$found_all" != 1 ]; then
  echo "--- app log ---"
  pid="$(adb shell pidof "$PKG" | tr -d '\r')"
  if [ -n "$pid" ]; then adb logcat -d --pid="$pid" | tail -300; else echo "$log" | tail -300; fi
  exit 1
fi
echo "Smoke test passed"

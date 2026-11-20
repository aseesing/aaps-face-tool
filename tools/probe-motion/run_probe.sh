#!/bin/sh
# usage: sh run_probe.sh <apk> <emulator serial> <out folder>
set -e
APK="$1"; DEVICE="$2"; OUT="$3"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
adb_() { "$ADB" -s "$DEVICE" "$@"; }
mkdir -p "$OUT"
LOCK="$HOME/.cache/aaps-wff/$DEVICE.lock"
while ! mkdir "$LOCK" 2>/dev/null; do
    if [ -f "$LOCK/pid" ] && ! ps -p "$(cat "$LOCK/pid")" >/dev/null 2>&1; then rm -rf "$LOCK"; continue; fi
    sleep 2
done
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT INT TERM
[ "$(adb_ shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] || { echo "$DEVICE is not an emulator"; exit 1; }
adb_ install -r "$APK" | tail -1
adb_ shell svc power stayon true
adb_ shell input keyevent KEYCODE_WAKEUP
SDK="$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')"
adb_ shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId app.aaps.probe.motion | tail -1
sleep 2
# Wear OS 6 leaves the face picker open: Back. On Wear OS 5, Back and Home switch between the face and
# the app list, so only press Home while the app list has the focus.
if [ "$SDK" -ge 36 ]; then
    adb_ shell input keyevent KEYCODE_BACK
elif adb_ shell dumpsys window | grep mCurrentFocus | grep -q AllAppsLauncher; then
    adb_ shell input keyevent KEYCODE_HOME
fi
sleep 12
for i in 1 2 3 4; do
    adb_ exec-out screencap -p > "$OUT/shot$i.png"
    sleep 1
done
adb_ shell screenrecord --time-limit 4 /data/local/tmp/probe.mp4
adb_ pull /data/local/tmp/probe.mp4 "$OUT/probe.mp4" >/dev/null
adb_ shell rm /data/local/tmp/probe.mp4
adb_ logcat -d -t 400 | grep -i -E "watchface|wff|runtime" | grep -i -E "error|exception|fail|invalid" | tail -20 > "$OUT/log.txt" || true
adb_ uninstall app.aaps.probe.motion >/dev/null || true
echo done

#!/bin/sh
# Shows a WFF face made by the WFF harness (docs/wff-apk.md) on a Wear OS 6 EMULATOR, with the values
# of its scene, and takes a picture of it next to the face as our own engine draws it.
#
# It gives the debug build of the AAPS wear app on the emulator the design and the values, as files
# (the stores of the app), so no phone is needed. It refuses any device that is not an emulator.
#
# usage: sh tools/wff-emulator.sh <design folder of the harness, e.g. build/wff/Aurora_Town> [wear app apk]
#   WFF_DEVICE  the adb serial of the emulator (default emulator-5554)
#   WFF_CLIENT  the application id of the wear app (default info.nightscout.androidaps)
#   WFF_WAIT    seconds to wait before the picture is taken (default 25: the face needs time to load)
#   WFF_KEEP    set to 1 to update the installed face and keep its slot choices
#   WFF_TIME    the clock of the emulator for the picture, in ms since 1970 (default: the time of this computer)
#   WFF_TZ      the time zone of the emulator, an Olson id like Europe/Amsterdam (default: leave it)
#   WFF_WAKE    a number of pictures to take during a wake after the picture at rest (tools/wff-wake.sh)
# Only one run at a time drives the emulator: a run waits for the lock ~/.cache/aaps-wff/emulator.lock.
# Give the wear app apk (a debug build: the script needs run-as) the first time, and after a change of the app.
# Output: <design folder>/emulator.png and compare.png (left: our engine, right: the emulator).
set -e
DIR="$1"
[ -f "$DIR/face.apk" ] || { echo "usage: sh tools/wff-emulator.sh <design folder with face.apk> [wear app apk]"; exit 2; }
WEAR_APK="$2"
DEVICE="${WFF_DEVICE:-emulator-5554}"
APP="${WFF_CLIENT:-info.nightscout.androidaps}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb
adb_() { "$ADB" -s "$DEVICE" "$@"; }

# One run at a time: the parity tests and people share the emulator
LOCK="${AAPS_WFF_CACHE:-$HOME/.cache/aaps-wff}/emulator.lock"
mkdir -p "$(dirname "$LOCK")"
WAITED=0
while ! mkdir "$LOCK" 2>/dev/null; do
    if [ -f "$LOCK/pid" ] && ! ps -p "$(cat "$LOCK/pid")" >/dev/null 2>&1; then rm -rf "$LOCK"; continue; fi
    WAITED=$((WAITED + 2)); [ "$WAITED" -gt 1800 ] && { echo "The emulator lock is held for 30 minutes: $LOCK"; exit 1; }
    sleep 2
done
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT INT TERM

[ "$(adb_ shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] || { echo "$DEVICE is not an emulator: this script never touches a real watch"; exit 1; }

# The clock of an emulator that slept can be minutes behind: the values of the scene would then lie in its future
adb_ shell settings put global auto_time 0
[ -n "$WFF_TZ" ] && adb_ shell cmd alarm set-timezone "$WFF_TZ" >/dev/null
adb_ shell cmd alarm set-time "${WFF_TIME:-$(date +%s)000}" >/dev/null

if [ -n "$WEAR_APK" ]; then adb_ install -r -d "$WEAR_APK" | tail -1; fi
adb_ shell pm list packages | grep -q "package:$APP\$" || { echo "The wear app $APP is not on the emulator: give its debug apk as the second argument"; exit 1; }

# The heart rate and the steps of the wearer's slots need these; on a watch the wearer taps "Allow"
for p in android.permission.BODY_SENSORS android.permission.ACTIVITY_RECOGNITION android.permission.health.READ_HEART_RATE; do
    adb_ shell pm grant "$APP" "$p" >/dev/null 2>&1 || true
done

# The design and the values, as the stores of the app
adb_ shell am force-stop "$APP"
TMP=/data/local/tmp/wff-scene
adb_ shell "rm -rf $TMP; mkdir -p $TMP"
put() { # <local file> <path in the app's data folder>
    adb_ push "$1" "$TMP/f" >/dev/null
    adb_ shell "cat $TMP/f | run-as $APP sh -c 'mkdir -p \$(dirname $2) && cat > $2'"
}
for f in "$DIR"/scene/datastore/*; do put "$f" "files/datastore/$(basename "$f")"; done
put "$DIR/scene/shared_prefs/watch_settings.xml" "shared_prefs/${APP}_preferences.xml"
put "$DIR/scene/shared_prefs/aaps_health_values.xml" "shared_prefs/aaps_health_values.xml"
adb_ shell "rm -rf $TMP"

# The face. Removed first, so the watch forgets which sources it gave to the slots: it only reads
# the defaults of a slot the first time it sees the slot. Set WFF_KEEP=1 to update the face in place.
[ -n "$WFF_KEEP" ] || adb_ uninstall "$APP.watchfacepush.design" >/dev/null 2>&1 || true
adb_ install -r "$DIR/face.apk" | tail -1
adb_ shell svc power stayon true
adb_ shell input keyevent KEYCODE_WAKEUP
adb_ shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId "$APP.watchfacepush.design" | tail -1
sleep 2
adb_ shell input keyevent KEYCODE_BACK
sleep "${WFF_WAIT:-25}"
# With a fixed time, set it again just before the picture: the face shows the minute of the reference
[ -n "$WFF_TIME" ] && adb_ shell cmd alarm set-time "$WFF_TIME" >/dev/null && sleep 2
adb_ exec-out screencap -p > "$DIR/emulator.tmp.png"
# A picture can come out cut off: take it again until it is a whole PNG (it ends with IEND)
for i in 1 2 3; do tail -c 12 "$DIR/emulator.tmp.png" | grep -q IEND && break; sleep 1; adb_ exec-out screencap -p > "$DIR/emulator.tmp.png"; done
mv "$DIR/emulator.tmp.png" "$DIR/emulator.png"
# The wake, while this run still holds the emulator
[ -n "$WFF_WAKE" ] && sh "$(dirname "$0")/wff-wake.sh" "$DIR" "$WFF_WAKE" >/dev/null
# A fixed time was only for this picture: the emulator follows the network time again
[ -n "$WFF_TIME" ] && adb_ shell settings put global auto_time 1
java "$(dirname "$0")/SideBySide.java" "$DIR/compare.png" 450 "$DIR/scene/reference.png" "$DIR/emulator.png"
echo "$DIR/compare.png"

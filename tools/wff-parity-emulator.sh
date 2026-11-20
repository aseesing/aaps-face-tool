#!/bin/sh
# The emulator driver of the parity suite (docs/wff-parity.md). Started by `./gradlew wffParityTest
# -PwffParityEmulator`; not meant to be run by hand.
#
# It works through the queue that the parity tests write (one test folder per line) while the tests
# still run, and stops when the done file appears and the queue is empty. Per face it pushes the
# design and the values of the scene into the stores of the wear app (only when they changed), installs
# the face, sets the clock and the watch battery of the scene, and takes the picture as soon as the
# screen stops changing. Afterwards the emulator follows the network time and its own battery again.
# A face whose files, scene, wear app and clock are the same as at its last picture keeps that picture.
#
# usage: sh tools/wff-parity-emulator.sh <queue file> <done file> [wear app apk]
#   WFF_DEVICE  the adb serial of the emulator (default emulator-5554)
#   WFF_SHARD   k/n: this driver takes every n-th face from the k-th on (0/1 = all). With two emulators
#               Gradle starts two drivers, 0/2 and 1/2, one per emulator.
#   WFF_CLIENT  the application id of the wear app (default info.nightscout.androidaps)
#   WFF_TZ      the time zone of the scene (default Europe/Amsterdam)
#   WFF_WAIT    at most this many seconds for a face to settle (default 10; a face with seconds or a
#               moving hand never settles and gets the full wait)
#   WFF_FRESH   set to 1 to take every picture again, also of faces that did not change
# The wear app apk (a debug build) is installed when it differs from the one installed last time.
set -e
QUEUE="$1"
DONE="$2"
WEAR_APK="$3"
[ -n "$QUEUE" ] && [ -n "$DONE" ] || { echo "usage: sh tools/wff-parity-emulator.sh <queue file> <done file> [wear app apk]"; exit 2; }
DEVICE="${WFF_DEVICE:-emulator-5554}"
APP="${WFF_CLIENT:-info.nightscout.androidaps}"
FACE="$APP.watchfacepush.design"
WAIT="${WFF_WAIT:-10}"
SHARD="${WFF_SHARD:-0/1}"
SHARD_K="${SHARD%/*}"
SHARD_N="${SHARD#*/}"
CACHE="${AAPS_WFF_CACHE:-$HOME/.cache/aaps-wff}"
TOOLS="$(cd "$(dirname "$0")" && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb
adb_() { "$ADB" -s "$DEVICE" "$@"; }

# One user of an emulator at a time, the same lock as tools/wff-emulator.sh
LOCK="$CACHE/$DEVICE.lock"
mkdir -p "$CACHE"
WAITED=0
while ! mkdir "$LOCK" 2>/dev/null; do
    if [ -f "$LOCK/pid" ] && ! ps -p "$(cat "$LOCK/pid")" >/dev/null 2>&1; then rm -rf "$LOCK"; continue; fi
    WAITED=$((WAITED + 2)); [ "$WAITED" -gt 1800 ] && { echo "The emulator lock is held for 30 minutes: $LOCK"; exit 1; }
    sleep 2
done
echo $$ > "$LOCK/pid"
trap 'adb_ shell settings put global auto_time 1 >/dev/null 2>&1; adb_ shell dumpsys battery reset >/dev/null 2>&1; rm -rf "$LOCK"' EXIT INT TERM

[ "$(adb_ shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] || { echo "$DEVICE is not an emulator: this script never touches a real watch"; exit 1; }
adb_ shell settings put global auto_time 0
adb_ shell cmd alarm set-timezone "${WFF_TZ:-Europe/Amsterdam}" >/dev/null

if [ -n "$WEAR_APK" ]; then
    SUM="$(shasum < "$WEAR_APK" | sed 's/ .*//')"
    if [ "$(cat "$CACHE/wear-apk-$DEVICE.sha" 2>/dev/null)" != "$SUM" ]; then
        echo "installing the wear app $WEAR_APK"
        adb_ install -r -d "$WEAR_APK" | tail -1
        echo "$SUM" > "$CACHE/wear-apk-$DEVICE.sha"
    fi
    # The same wear app on every emulator gives the same picture: a face keeps its picture whichever emulator took it
    APP_VERSION="$SUM"
fi
adb_ shell pm list packages | grep -q "package:$APP\$" || { echo "The wear app $APP is not on the emulator"; exit 1; }
[ -n "$APP_VERSION" ] || APP_VERSION="$(adb_ shell dumpsys package "$APP" | grep -m1 lastUpdateTime | sed 's/^ *//')"
for p in android.permission.BODY_SENSORS android.permission.ACTIVITY_RECOGNITION android.permission.health.READ_HEART_RATE; do
    adb_ shell pm grant "$APP" "$p" >/dev/null 2>&1 || true
done
adb_ shell svc power stayon true

TMP=/data/local/tmp/wff-scene
put() { # <local file> <path in the app's data folder>
    adb_ push "$1" "$TMP/f" >/dev/null 2>&1
    adb_ shell "cat $TMP/f | run-as $APP sh -c 'mkdir -p \$(dirname $2) && cat > $2'"
}
LAST_STORE=""

# Shows the face of one test folder and takes its pictures
face() {
    DIR="$1"
    START=$(date +%s)
    AT="$(sed -n 's/^atMs=//p' "$DIR/parity.job")"
    WAKE="$(sed -n 's/^wake=//p' "$DIR/parity.job")"
    REST_AFTER="$(sed -n 's/^restAfterSeconds=//p' "$DIR/parity.job")"
    MIN=4
    [ -n "$REST_AFTER" ] && [ "$REST_AFTER" -gt "$MIN" ] && MIN="$REST_AFTER"
    LIMIT=$((WAIT > MIN + 2 ? WAIT : MIN + 2))
    # The battery of the watch in the scene, when it sets one: both our engine and WFF read it from the system
    BATTERY="$(cat "$DIR/scene/watch_battery.txt" 2>/dev/null || true)"
    STORE="$(cd "$DIR/scene" && find datastore shared_prefs -type f | sort | xargs shasum | shasum | sed 's/ .*//')"
    KEY="$( (cd "$DIR" && find face/res -type f | sort | xargs shasum; echo "$STORE $APP_VERSION $AT $WAKE $BATTERY") | shasum | sed 's/ .*//')"
    if [ -z "$WFF_FRESH" ] && [ -f "$DIR/emulator.png" ] && [ "$(cat "$DIR/emulator.key" 2>/dev/null)" = "$KEY" ]; then
        echo "same as before: $DIR"
        return
    fi
    [ -f "$DIR/emulator.key" ] && echo "changed: $DIR (key $(cat "$DIR/emulator.key") -> $KEY)"
    rm -f "$DIR/emulator.png" "$DIR/emulator.key" "$DIR"/wake_*.png

    # The design and the values. The app reads them when it starts, so it is stopped first.
    if [ "$STORE" != "$LAST_STORE" ]; then
        adb_ shell am force-stop "$APP"
        adb_ shell "rm -rf $TMP; mkdir -p $TMP"
        for f in "$DIR"/scene/datastore/*; do put "$f" "files/datastore/$(basename "$f")"; done
        put "$DIR/scene/shared_prefs/watch_settings.xml" "shared_prefs/${APP}_preferences.xml"
        put "$DIR/scene/shared_prefs/aaps_health_values.xml" "shared_prefs/aaps_health_values.xml"
        LAST_STORE="$STORE"
    fi

    if [ -n "$BATTERY" ]; then adb_ shell dumpsys battery set level "$BATTERY"; else adb_ shell dumpsys battery reset; fi

    T_STORE=$(date +%s)
    # Removed first: the watch gives a slot its default source only the first time it sees the slot
    adb_ uninstall "$FACE" >/dev/null 2>&1 || true
    adb_ install -r "$DIR/face.apk" >/dev/null
    adb_ shell cmd alarm set-time "$AT" >/dev/null
    adb_ shell input keyevent KEYCODE_WAKEUP
    adb_ shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId "$FACE" >/dev/null
    sleep 1
    adb_ shell input keyevent KEYCODE_BACK
    T_SHOWN=$(date +%s)

    # The picture, when three pictures a second apart are the same (a face with seconds never is: it
    # gets the full wait). A picture can come out cut off: only whole ones (ending with IEND) count.
    PREV=""
    SAME=0
    T=0
    while [ "$T" -lt "$LIMIT" ]; do
        sleep 1
        T=$((T + 1))
        adb_ exec-out screencap -p > "$DIR/emulator.tmp.png"
        tail -c 12 "$DIR/emulator.tmp.png" | grep -q IEND || continue
        SUM="$(shasum < "$DIR/emulator.tmp.png")"
        if [ "$SUM" = "$PREV" ]; then SAME=$((SAME + 1)); else SAME=0; fi
        PREV="$SUM"
        [ "$T" -ge "$MIN" ] && [ "$SAME" -ge 2 ] && break
    done
    mv "$DIR/emulator.tmp.png" "$DIR/emulator.png"
    [ "$WAKE" = "true" ] && sh "$TOOLS/wff-wake.sh" "$DIR" 4 >/dev/null
    echo "$KEY" > "$DIR/emulator.key"
    echo "$(( $(date +%s) - START )) s: $DIR (stores $((T_STORE - START)) s, install $((T_SHOWN - T_STORE)) s, settle $T s)"
}

N=0
while :; do
    TOTAL=0
    [ -f "$QUEUE" ] && TOTAL=$(wc -l < "$QUEUE" | sed 's/ //g')
    if [ "$N" -lt "$TOTAL" ]; then
        N=$((N + 1))
        [ $(( (N - 1) % SHARD_N )) -eq "$SHARD_K" ] || continue
        DIR="$(sed -n "${N}p" "$QUEUE")"
        # A face that fails does not stop the others: the compare step reports its missing picture
        [ -n "$DIR" ] && { face "$DIR" || echo "failed: $DIR"; }
        continue
    fi
    [ -f "$DONE" ] && break
    sleep 1
done
echo "done: $N faces in the queue, this driver took shard $SHARD"

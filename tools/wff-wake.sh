#!/bin/sh
# Takes pictures of the wake-up effects of the WFF face that is on the EMULATOR now (put there with
# tools/wff-emulator.sh): turns the screen off and on, and takes a picture every second or so.
#
# usage: sh tools/wff-wake.sh <design folder of the harness> [number of pictures, default 5]
# Output: <design folder>/wake.png, the pictures side by side, the first one soonest after the wake.
set -e
DIR="$1"
[ -d "$DIR" ] || { echo "usage: sh tools/wff-wake.sh <design folder> [number of pictures]"; exit 2; }
COUNT="${2:-5}"
DEVICE="${WFF_DEVICE:-emulator-5554}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb
adb_() { "$ADB" -s "$DEVICE" "$@"; }
[ "$(adb_ shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] || { echo "$DEVICE is not an emulator: this script never touches a real watch"; exit 1; }

adb_ shell svc power stayon false
adb_ shell input keyevent KEYCODE_SLEEP
sleep 4
adb_ shell input keyevent KEYCODE_WAKEUP
FILES=""
i=0
while [ "$i" -lt "$COUNT" ]; do
    adb_ exec-out screencap -p > "$DIR/wake_$i.png"
    FILES="$FILES $DIR/wake_$i.png"
    i=$((i + 1))
    sleep 0.6
done
adb_ shell svc power stayon true
# shellcheck disable=SC2086
java "$(dirname "$0")/SideBySide.java" "$DIR/wake.png" 300 $FILES
echo "$DIR/wake.png"

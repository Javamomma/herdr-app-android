#!/bin/sh
# Takes the README and announcement screenshots: the app in demo mode on an
# emulator sized like a Galaxy Z Fold 7 (inner screen unfolded, cover screen
# folded). Run by CI after the APK builds; needs adb and a running emulator.
set -eu
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
OUT=${2:-screenshots}
PKG=io.github.javamomma.herdr
ACT=$PKG/herdr.app.MainActivity
mkdir -p "$OUT"

adb install -r -g "$APK"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "took $1"; }
start() { adb shell am start -W -S -n $ACT "$@" > /dev/null; }
open() { adb shell am start -W -n $ACT "$@" > /dev/null; }
size() { adb shell wm size "$1"; adb shell wm density "$2"; }

# Unfolded inner screen: 1968x2184, about 750 dp wide.
size 1968x2184 420
start
shot 01-connect 4
start --ez demo true
shot 02-unfolded-workspace 7
# Codex hits a permission prompt 15 s into the demo: its notification.
sleep 10
adb shell cmd statusbar expand-notifications
shot 03-notification 2
adb shell cmd statusbar collapse

# Unfolded on its side, wide enough for the Files panel beside the panes.
size 2184x1968 360
open --es screen changes
shot 04-unfolded-changes 4

# Folded cover screen: 1080x2520, about 411 dp wide.
size 1080x2520 420
open --es screen agents
shot 05-folded-agents 4
open --es screen work
shot 06-folded-terminal 3
open --es screen snippets
shot 07-folded-snippets 3

adb shell wm size reset
adb shell wm density reset

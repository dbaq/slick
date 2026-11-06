#!/bin/sh
# Checks Home lands on Slick and that Settings, Wi-Fi and every dock app reach the foreground.
set -u
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s ${TV:?set TV=<tv-ip>:5555}"
top() { $ADB shell dumpsys activity activities </dev/null | grep -m1 mResumedActivity | awk '{print $4}'; }
check() {
  sleep "$2"
  got=$(top)
  case "$got" in "$1"*) echo "ok    $1" ;; *) echo "FAIL  $1 (top: $got)" ;; esac
}
home() { $ADB shell input keyevent KEYCODE_HOME </dev/null; check tv.slick 3; }

$ADB logcat -c </dev/null
home
$ADB shell am start -n com.amazon.tv.launcher/.ui.MainSettingsActivity </dev/null >/dev/null; check com.amazon.tv.launcher/.ui.MainSettingsActivity 4; home
$ADB shell am start -a android.settings.WIFI_SETTINGS </dev/null >/dev/null; check com.amazon.tv.settings.v2 4; home
for pkg in com.disney.disneyplus com.netflix.ninja com.amazon.firebat org.smarttube.beta com.stremio.one com.plexapp.android; do
  $ADB shell monkey -p "$pkg" -c android.intent.category.LEANBACK_LAUNCHER 1 </dev/null >/dev/null 2>&1
  check "$pkg" 8
  home
done
echo "crashes:"; $ADB logcat -d -b crash </dev/null | grep -E "FATAL|Process:" | sort -u | head

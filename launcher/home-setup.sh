#!/bin/sh
# Sends a TV its Home Assistant setup: the address and token from config.properties, and the devices on
# its panel from a devices file (one "entity_id=Name" per line; Name may be empty). Run it again to reset
# the TV's list; after that, change the list in Slick's Settings > Home controls. The doorbell (ha_doorbell
# in config.properties: the ring sensor, then the cameras to show, separated by ";") goes to every TV.
#   sh launcher/home-setup.sh <tv-ip> launcher/home-living-room.devices
set -eu
[ $# -eq 2 ] || { echo "usage: $0 <tv-ip> <devices-file>" >&2; exit 1; }
here=$(dirname "$0")
adb=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
prop() { sed -n "s/^$1=//p" "$here/config.properties"; }
url=$(prop ha_url)
token=$(prop ha_token)
doorbell=$(prop ha_doorbell)
[ -n "$url" ] && [ -n "$token" ] || { echo "ha_url and ha_token must be set in config.properties" >&2; exit 1; }
devices=$(grep -v '^[[:space:]]*\(#\|$\)' "$2" | tr '\n' ';')
case "$devices" in *\'*) echo "device names can't contain a single quote" >&2; exit 1 ;; esac
"$adb" connect "$1:5555" >/dev/null </dev/null
# adb shell hands the line to the TV's shell, so each value is single-quoted for it.
"$adb" -s "$1:5555" shell "am broadcast -n tv.slick/.HomeSetup -a tv.slick.SET_HOME --es url '$url' --es token '$token' --es devices '$devices' --es doorbell '$doorbell'" </dev/null >/dev/null
echo "Sent $(printf %s "$devices" | tr ';' '\n' | grep -c .) devices to $1"

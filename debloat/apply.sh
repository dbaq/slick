#!/bin/sh
# Usage: apply.sh disable|enable <list.txt>...  — pm disable-user / enable each package for user 0.
set -eu
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s ${TV:?set TV=<tv-ip>:5555}"
case "$1" in
  disable) cmd="pm disable-user --user 0" ;;
  enable) cmd="pm enable --user 0" ;;
  *) echo "usage: $0 disable|enable <list>..." >&2; exit 1 ;;
esac
shift
cat "$@" | while read -r pkg; do
  printf '%-55s %s\n' "$pkg" "$($ADB shell $cmd "$pkg" </dev/null 2>&1 | tr -d '\r' | tail -1)"
done

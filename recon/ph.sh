#!/bin/bash
# Dev helper for driving the phone from the Mac.
#   ph.sh tap X Y | ph.sh type "text" | ph.sh key ENTER|BACK|HOME | ph.sh shot NAME | ph.sh log [N] | ph.sh cmd ACTION key val ...
A="$HOME/Library/Android/sdk/platform-tools/adb"
OUT="$HOME/projects/prism-teachable-voice/recon/local"
mkdir -p "$OUT"
case "$1" in
  tap)  "$A" shell input tap "$2" "$3" ;;
  swipe) "$A" shell input swipe "$2" "$3" "$4" "$5" "${6:-350}" ;;
  type) "$A" shell input text "$(printf '%s' "$2" | sed 's/ /%s/g')" ;;
  key)  "$A" shell input keyevent "KEYCODE_$2" ;;
  shot) "$A" exec-out screencap -p > "$OUT/$2.png" && python3 -c "
from PIL import Image; im=Image.open('$OUT/$2.png').convert('RGB'); im.resize((im.width//3, im.height//3)).save('$OUT/$2.jpg', quality=75)" && echo "$OUT/$2.jpg" ;;
  log)  "$A" shell tail -n "${2:-30}" /sdcard/Android/data/com.prism.tva/files/debug.log ;;
  cmd)  shift; act="$1"; shift; q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
        c="am broadcast -p com.prism.tva -a com.prism.tva.$act"
        while [ $# -ge 2 ]; do c="$c --es $1 $(q "$2")"; shift 2; done
        "$A" shell "$c" >/dev/null ;;
  focus) "$A" shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r' ;;
esac

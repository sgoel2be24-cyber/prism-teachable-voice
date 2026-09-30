#!/bin/bash
# The judge's path, as far as adb can play it: type the command in the app, press TEACH, do the task
# with taps, press Done on the teaching pill in the status bar (not the dev broadcast).
#   teach_github_ui.sh ["command"] ["typed text"]
cd "$(dirname "$0")"; A="$HOME/Library/Android/sdk/platform-tools/adb"
GH=com.github.android
center() { awk '{print $NF}' | awk -F, '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'; }
"$A" shell am force-stop $GH
"$A" shell monkey -p com.prism.tva -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
./ph.sh tap 276 1105; sleep 1                      # "…or type a command"
"$A" shell input text "$(printf '%s' "${1:-Open the flutter repository on GitHub}" | sed 's/ /%s/g')"; sleep 1
"$A" shell input keyevent BACK; sleep 1             # hide the keyboard
./ph.sh tap 894 1105; sleep 3                      # TEACH
"$A" shell dumpsys window windows | grep -A3 "u0 com.prism.tva}" | grep mFrame
"$A" shell monkey -p $GH -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
./ph.sh tap 564 194; sleep 3
"$A" shell input text "${2:-flutter}"; sleep 3
xy=$(./ui.py "Repositories with" | grep -vE "^app=" | head -1 | center); echo "repositories at: $xy"
[ -n "$xy" ] && ./ph.sh tap $xy; sleep 9
xy=$(./ui.py "container" | grep -vE "^app=" | head -1 | center); echo "first result at: $xy"
[ -n "$xy" ] && ./ph.sh tap $xy; sleep 5
"$A" shell dumpsys window windows | grep -A3 "u0 com.prism.tva}" | grep mFrame
./ph.sh tap ${DONE:-563 43}; sleep 6                 # Done on the pill

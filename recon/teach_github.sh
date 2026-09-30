#!/bin/bash
# Scripted teach in an app the assistant has never seen (GitHub, read-only): search a repository and
# open it. Checks a brand-new flow learns its value, then replays with another one.
#   teach_github.sh ["spoken command"] ["typed text"]
cd "$(dirname "$0")"; A="$HOME/Library/Android/sdk/platform-tools/adb"
GH=com.github.android
center() { awk '{print $NF}' | awk -F, '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'; }
"$A" shell am force-stop $GH
./ph.sh cmd TEACH_START command "${1:-Open the pytorch repository on GitHub}"; sleep 2
"$A" shell monkey -p $GH -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
xy=$(./ui.py "'Search'" | head -2 | tail -1 | center); echo "search at: $xy"
./ph.sh tap ${xy:-564 194}; sleep 3
"$A" shell input text "${2:-pytorch}"; sleep 3
xy=$(./ui.py "Repositories with" | grep -vE "^app=" | head -1 | center); echo "repositories at: $xy"
[ -n "$xy" ] && ./ph.sh tap $xy; sleep 9
xy=$(./ui.py "container" | grep -vE "^app=" | head -1 | center); echo "first result at: $xy"
[ -n "$xy" ] && ./ph.sh tap $xy; sleep 6
./ph.sh cmd TEACH_STOP; sleep 4

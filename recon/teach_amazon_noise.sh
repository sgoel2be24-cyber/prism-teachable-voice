#!/bin/bash
# Scripted teach session for the Amazon flow WITH noise (adb taps stand in for the user's finger):
# mid-demo the "user" switches to the Calculator, taps it, and comes back with the back gesture
# (this phone uses gesture navigation). The learned recipe should drop the detour and still replay.
cd "$(dirname "$0")"; A="$HOME/Library/Android/sdk/platform-tools/adb"
AMZ=in.amazon.mShop.android.shopping
center() { awk '{print $NF}' | awk -F, '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'; }
"$A" shell am force-stop $AMZ
./ph.sh cmd TEACH_START command "${1:-Search for a steel water bottle on Amazon and add the first result to cart}"; sleep 2
"$A" shell monkey -p $AMZ -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
./ph.sh tap 400 420; sleep 2.5
"$A" shell input text "${2:-steel%swater%sbottle}"; sleep 2
"$A" shell input keyevent KEYCODE_ENTER; sleep 7
# Noise 1: another app.
"$A" shell monkey -p com.coloros.calculator -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
./ph.sh tap 540 1700; sleep 1.5
./ph.sh swipe 3 1300 450 1300 250; sleep 8   # back gesture returns to Amazon's results (they re-render)
xy=""
for i in 1 2 3 4; do
  ./ph.sh swipe 540 1700 540 900 400; sleep 3
  xy=$(./ui.py "Add to cart" | grep -E "^C +Button" | head -1 | center)
  [ -n "$xy" ] && break
done
echo "Add to cart at: $xy"; [ -n "$xy" ] && ./ph.sh tap $xy; sleep 4
./ph.sh tap 630 2268; sleep 5
./ph.sh cmd TEACH_STOP; sleep 4

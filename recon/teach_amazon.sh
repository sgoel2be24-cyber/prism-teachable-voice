#!/bin/bash
# Scripted teach session for the Amazon flow (adb taps stand in for the user's finger).
#   teach_amazon.sh ["spoken command"] ["typed%squery"]
cd "$(dirname "$0")"; A="$HOME/Library/Android/sdk/platform-tools/adb"
AMZ=in.amazon.mShop.android.shopping
center() { awk '{print $NF}' | awk -F, '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'; }
"$A" shell am force-stop $AMZ
./ph.sh cmd TEACH_START command "${1:-Search for wireless earbuds on Amazon and add the first result to cart}"; sleep 2
"$A" shell monkey -p $AMZ -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
./ph.sh tap 400 420; sleep 2.5
"$A" shell input text "${2:-wireless%searbuds}"; sleep 2
"$A" shell input keyevent KEYCODE_ENTER; sleep 8
xy=""
for i in 1 2 3 4; do
  ./ph.sh swipe 540 1700 540 900 400; sleep 3
  xy=$(./ui.py "Add to cart" | grep -E "^C +Button" | head -1 | center)
  [ -n "$xy" ] && break
done
echo "Add to cart at: $xy"; [ -n "$xy" ] && ./ph.sh tap $xy; sleep 4
./ph.sh tap 630 2268; sleep 5
./ph.sh cmd TEACH_STOP; sleep 4

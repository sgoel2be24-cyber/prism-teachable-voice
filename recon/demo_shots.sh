#!/bin/bash
# Screenshots for the deck's walkthrough slide (saved in recon/local/, which is git-ignored):
# teaching, a run in progress, the hand-back at checkout, and a question.
cd "$(dirname "$0")"; A="$HOME/Library/Android/sdk/platform-tools/adb"
LOG=/sdcard/Android/data/com.prism.tva/files/debug.log
shot() { "$A" exec-out screencap -p > "local/shot_$1.png"; echo "shot $1"; }
waitlog() {  # waitlog <pattern> <timeout s>: returns when a new log line matches
  local n0; n0=$("$A" shell grep -c "$1" $LOG | tr -d '\r'); local t0=$(date +%s)
  while [ $(( $(date +%s) - t0 )) -lt "$2" ]; do
    [ "$("$A" shell grep -c "$1" $LOG | tr -d '\r')" != "$n0" ] && return 0; sleep 0.4
  done; return 1
}
"$A" shell input keyevent KEYCODE_WAKEUP

# 1. Teaching: the pill in the status bar, the red border, a search in Zomato.
"$A" shell am force-stop com.application.zomato
./ph.sh cmd TEACH_START command "Order a Margherita pizza from Domino's on Zomato"; sleep 2
"$A" shell monkey -p com.application.zomato -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 9
./ph.sh tap 468 381; sleep 3
"$A" shell input text "dominos"; sleep 4
shot teach
./ph.sh cmd TEACH_CANCEL; sleep 2

# 2. A run in progress, then the hand-back.
"$A" shell am force-stop com.application.zomato; "$A" shell input keyevent HOME; sleep 2
./tva.sh RUN utterance "Get me a margherita from dominos"
waitlog 'TAP "ADD"' 60 && sleep 1.2 && shot run
waitlog "RUN_END" 90 && sleep 0.6 && shot done

# 3. A question (then "stop", so nothing more is added).
"$A" shell am force-stop com.application.zomato; "$A" shell input keyevent HOME; sleep 3
./tva.sh RUN utterance "I want to order margherita pizza on zomato"
waitlog "ASK " 60 && sleep 1.5 && shot ask
./tva.sh RUN utterance "stop"; sleep 2
echo done

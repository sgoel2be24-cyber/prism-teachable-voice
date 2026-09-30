#!/bin/bash
# A replay in progress on the Domino's menu, for the deck (saved in recon/local/, git-ignored).
cd "$(dirname "$0")"; A="$HOME/Library/Android/sdk/platform-tools/adb"
LOG=/sdcard/Android/data/com.prism.tva/files/debug.log
shot() { "$A" exec-out screencap -p > "local/shot_$1.png"; echo "shot $1 $(date +%T)"; }
count() { "$A" shell "grep -c '$1' $LOG" | tr -d '\r'; }
waitlog() {  # waitlog <pattern> <timeout s>: returns when a new log line matches
  local n0; n0=$(count "$1"); local t0=$(date +%s)
  while [ $(( $(date +%s) - t0 )) -lt "$2" ]; do
    [ "$(count "$1")" != "$n0" ] && return 0; sleep 0.3
  done; echo "timeout $1"; return 1
}
"$A" shell input keyevent KEYCODE_WAKEUP
"$A" shell am force-stop com.application.zomato; "$A" shell input keyevent HOME; sleep 2
./tva.sh RUN utterance "Get me a margherita from dominos"
waitlog "STEP 3 ok" 60 && sleep 0.5 && shot run_s3
waitlog "STEP 5 skipped" 60 && sleep 1.2 && shot run_m1 && sleep 1.6 && shot run_m2 && sleep 1.6 && shot run_m3
waitlog "RUN_END" 90 && sleep 0.8 && shot done2
echo finished

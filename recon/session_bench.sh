#!/bin/bash
# Replay-across-sessions bench: for each command, fully close the target app, go home, then run the
# command through the assistant (same path as a spoken command) and wait for the run to end.
#   session_bench.sh <app-package> "command 1" "command 2" ...
# Results land in the phone's runs.jsonl; summarise with session_report.py <start-epoch-ms>.
A="$HOME/Library/Android/sdk/platform-tools/adb"
D="$(dirname "$0")"
LOG=/sdcard/Android/data/com.prism.tva/files/debug.log
PKG="$1"; shift
echo "start_ms=$(( $(date +%s) * 1000 ))"
for u in "$@"; do
  "$A" shell am force-stop "$PKG"
  "$A" shell input keyevent HOME
  sleep 3
  n0=$("$A" shell grep -c "RUN_END" $LOG | tr -d '\r')
  "$D/tva.sh" RUN utterance "$u"
  t0=$(date +%s)
  while [ $(( $(date +%s) - t0 )) -lt 150 ]; do
    sleep 3
    n=$("$A" shell grep -c "RUN_END" $LOG | tr -d '\r')
    [ "$n" != "$n0" ] && break
  done
  echo "$(date +%T) $("$A" shell grep "RUN_END" $LOG | tail -1 | tr -d '\r' | cut -c1-160)"
  sleep 4
done
echo done

#!/bin/bash
# Reliability bench: run each command N times from a cold start; on a failed run, dump the screen the
# run stopped on (dumps/fail_<n>.json + a screenshot in recon/local/, which is git-ignored).
#   repeat_bench.sh <app-package> <times> "command 1" ["command 2" ...]
A="$HOME/Library/Android/sdk/platform-tools/adb"
D="$(dirname "$0")"
LOG=/sdcard/Android/data/com.prism.tva/files/debug.log
PKG="$1"; N="$2"; shift 2
mkdir -p "$D/local"
echo "start_ms=$(( $(date +%s) * 1000 ))"
k=0
for i in $(seq 1 "$N"); do
  for u in "$@"; do
    k=$((k + 1))
    "$A" shell am force-stop "$PKG"
    "$A" shell input keyevent HOME
    sleep 3
    n0=$("$A" shell grep -c "RUN_END" $LOG | tr -d '\r')
    "$D/tva.sh" RUN utterance "$u"
    t0=$(date +%s)
    while [ $(( $(date +%s) - t0 )) -lt 180 ]; do
      sleep 3
      n=$("$A" shell grep -c "RUN_END" $LOG | tr -d '\r')
      [ "$n" != "$n0" ] && break
    done
    # Still running after 3 minutes: stop it so the next run doesn't overlap it.
    [ "$n" == "$n0" ] && { "$D/tva.sh" STOP; sleep 8; }
    last=$("$A" shell grep "RUN_END" $LOG | tail -1 | tr -d '\r' | cut -c1-160)
    echo "$(date +%T) #$k [$u] $last"
    if ! echo "$last" | grep -q -E " (success|handover) "; then
      "$D/tva.sh" DUMP name "fail_$k"
      "$A" exec-out screencap -p > "$D/local/fail_$k.png"
      echo "   dumped fail_$k"
    fi
    sleep 4
  done
done
echo done

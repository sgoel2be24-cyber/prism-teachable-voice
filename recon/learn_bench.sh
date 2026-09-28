#!/bin/bash
# Learn-success bench: teach a flow by (scripted) demonstration, then immediately replay the new
# recipe once with a different value from a cold start. Deletes each test recipe afterwards so the
# phone keeps only the recipes you taught yourself.
#   learn_bench.sh "command|typed%squery|replay value" ...
cd "$(dirname "$0")"; A="$HOME/Library/Android/sdk/platform-tools/adb"
LOG=/sdcard/Android/data/com.prism.tva/files/debug.log
AMZ=in.amazon.mShop.android.shopping
for spec in "$@"; do
  IFS='|' read -r cmd typed value <<< "$spec"
  n0=$("$A" shell grep -c "LEARNED" $LOG | tr -d '\r')
  ./teach_amazon.sh "$cmd" "$typed" >/dev/null 2>&1
  for i in $(seq 1 20); do
    n=$("$A" shell grep -c "LEARNED" $LOG | tr -d '\r'); [ "$n" != "$n0" ] && break; sleep 3
  done
  line=$("$A" shell grep "LEARNED" $LOG | tail -1 | tr -d '\r')
  id=$(echo "$line" | awk '{print $3}')
  echo "TAUGHT  $cmd -> $id :: $(echo "$line" | cut -d' ' -f4- | cut -c1-140)"
  "$A" shell am force-stop $AMZ; "$A" shell input keyevent HOME; sleep 3
  r0=$("$A" shell grep -c "RUN_END" $LOG | tr -d '\r')
  ./tva.sh RUN_RECIPE id "$id" slots "{\"product\":\"$value\",\"query\":\"$value\",\"item\":\"$value\"}"
  for i in $(seq 1 50); do
    sleep 3; r=$("$A" shell grep -c "RUN_END" $LOG | tr -d '\r'); [ "$r" != "$r0" ] && break
  done
  echo "REPLAY  $value :: $("$A" shell grep "RUN_END" $LOG | tail -1 | tr -d '\r' | cut -c1-150)"
  ./tva.sh DELETE id "$id"; sleep 2
done
echo done

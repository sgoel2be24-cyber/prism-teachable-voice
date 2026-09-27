#!/bin/bash
# Dev helper: watch the assistant's log and answer its spoken questions (stands in for the user's voice).
#   autoanswer.sh SECONDS "answer if the question mentions X=..." ...   e.g.  autoanswer.sh 90 "size=UK 8" "*=yes"
A="$HOME/Library/Android/sdk/platform-tools/adb"
LOG=/sdcard/Android/data/com.prism.tva/files/debug.log
end=$(( $(date +%s) + ${1:-60} )); shift
seen=$("$A" shell grep -c "ASK " $LOG | tr -d '\r')
while [ "$(date +%s)" -lt "$end" ]; do
  n=$("$A" shell grep -c "ASK " $LOG | tr -d '\r')
  if [ "$n" != "$seen" ]; then
    seen=$n
    q=$("$A" shell grep "ASK " $LOG | tail -1 | tr -d '\r')
    case "$q" in *"no microphone"*|*"recognizer error"*) sleep 1; continue ;; esac
    ans=""
    for rule in "$@"; do
      key="${rule%%=*}"; val="${rule#*=}"
      if [ "$key" = "*" ] || echo "$q" | grep -qi "$key"; then ans="$val"; break; fi
    done
    if [ -n "$ans" ]; then
      sleep 2
      "$(dirname "$0")/ph.sh" cmd ANSWER text "$ans"
      echo "Q: ${q#*ASK }  ->  A: $ans"
    fi
  fi
  sleep 1
done

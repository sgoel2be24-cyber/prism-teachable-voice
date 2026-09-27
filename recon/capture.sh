#!/bin/bash
# Auto-capture the phone's UI tree (uiautomator XML) + screenshot whenever the screen changes.
# Usage: ./capture.sh <label>   e.g. ./capture.sh zomato   (Ctrl-C or kill to stop)
A="$HOME/Library/Android/sdk/platform-tools/adb"
LABEL="${1:-session}"
OUT="$HOME/projects/prism-teachable-voice/recon/$LABEL"
mkdir -p "$OUT"
n=$(ls "$OUT"/*.xml 2>/dev/null | wc -l | tr -d ' ')
last=""
fails=0
echo "capturing to $OUT (starting at #$((n+1)))"
while true; do
  if "$A" shell uiautomator dump /sdcard/tva_dump.xml >/dev/null 2>&1 \
     && "$A" pull /sdcard/tva_dump.xml "$OUT/.cur.xml" >/dev/null 2>&1; then
    fails=0
    # screen signature = element ids + labels (ignores positions, so scrolling within a list doesn't spam)
    sig=$(grep -o 'resource-id="[^"]*"\|text="[^"]*"\|content-desc="[^"]*"' "$OUT/.cur.xml" | md5 -q)
    if [ "$sig" != "$last" ]; then
      n=$((n+1)); f=$(printf "%03d" "$n")
      mv "$OUT/.cur.xml" "$OUT/$f.xml"
      "$A" exec-out screencap -p > "$OUT/$f.png"
      act=$("$A" shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r' | sed 's/.*u0 //; s/}//')
      echo "$f $(date +%H:%M:%S) $act" | tee -a "$OUT/index.txt"
      last="$sig"
    fi
  else
    fails=$((fails+1))
    echo "$(date +%H:%M:%S) dump failed (#$fails in a row) — screen may be animating" >> "$OUT/errors.txt"
  fi
  sleep 1
done

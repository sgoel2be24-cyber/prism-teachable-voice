#!/bin/bash
# Dev helper: send a test command to the spike service.  ./tva.sh CLICK text "Domino's Pizza"
A="$HOME/Library/Android/sdk/platform-tools/adb"
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
act="$1"; shift
cmd="am broadcast -p com.prism.tva -a com.prism.tva.$act"
while [ $# -ge 2 ]; do cmd="$cmd --es $1 $(q "$2")"; shift 2; done
"$A" shell "$cmd" >/dev/null

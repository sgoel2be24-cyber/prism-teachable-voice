#!/bin/bash
A="$HOME/Library/Android/sdk/platform-tools/adb"; R="$HOME/projects/prism-teachable-voice/recon/spike"; T="$HOME/projects/prism-teachable-voice/recon/tva.sh"
"$A" shell input keyevent KEYCODE_HOME; sleep 1
"$A" shell am force-stop com.application.zomato; sleep 1
"$A" shell monkey -p com.application.zomato -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
$T MARK note replay-test-start
$T DUMP name z_home; sleep 1
$T CLICK id search_bar_view_flipper; sleep 2
$T TYPE text dominos; sleep 3
"$A" exec-out screencap -p > "$R/rt_1_typed.png"
$T CLICK text "Domino's Pizza"; sleep 7
"$A" exec-out screencap -p > "$R/rt_2_after_suggestion.png"
$T DUMP name z_menu; sleep 2
"$A" shell cat /sdcard/Android/data/com.prism.tva/files/events.jsonl | sed -n '/replay-test-start/,$p' | grep '"type":"CMD_'

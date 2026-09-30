#!/usr/bin/env python3
"""Dev helper: pull runs.jsonl from the phone and print the runs since a time as a markdown table.

usage: runs_table.py "2026-09-30 09:00"      (local time)
"""
import datetime as dt, json, os, subprocess, sys

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
out = subprocess.run([ADB, "shell", "cat", "/sdcard/Android/data/com.prism.tva/files/runs.jsonl"],
                     capture_output=True, text=True).stdout
since = dt.datetime.strptime(sys.argv[1], "%Y-%m-%d %H:%M").timestamp() * 1000 if len(sys.argv) > 1 else 0
apps = {"in.amazon.mShop.android.shopping": "Amazon", "com.application.zomato": "Zomato",
        "com.myntra.android": "Myntra", "com.github.android": "GitHub"}
print("| Time | Command | App | Outcome | Time taken | Model calls | Note |")
print("|---|---|---|---|---|---|---|")
for line in out.splitlines():
    try:
        r = json.loads(line)
    except ValueError:
        continue
    if r.get("startedAt", 0) < since:
        continue
    t = dt.datetime.fromtimestamp(r["startedAt"] / 1000).strftime("%H:%M")
    note = (r.get("reason") or "").replace("|", "/")[:90]
    print(f"| {t} | {r.get('utterance', '')} | {apps.get(r.get('app'), r.get('app'))} | {r.get('outcome')} "
          f"| {r.get('ms', 0) / 1000:.0f} s | {r.get('llmCalls', 0)} | {note} |")

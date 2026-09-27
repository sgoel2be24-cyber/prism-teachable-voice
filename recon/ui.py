#!/usr/bin/env python3
"""Dev helper: ask the service for a screen dump, pull it, and print the app's elements.

usage: ui.py [filter-regex] [--all]      (--all includes non-clickable, unlabeled nodes)
"""
import json, os, re, subprocess, sys, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
DEV = os.path.expanduser("~/projects/prism-teachable-voice/recon/local")
os.makedirs(DEV, exist_ok=True)


def sh(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


def dump():
    sh("shell", "am broadcast -p com.prism.tva -a com.prism.tva.DUMP --es name ui")
    time.sleep(1.2)
    out = os.path.join(DEV, "ui.json")
    sh("pull", "/sdcard/Android/data/com.prism.tva/files/dumps/ui.json", out)
    return json.load(open(out))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    show_all = "--all" in sys.argv
    pat = re.compile(args[0], re.I) if args else None
    d = dump()
    print(f"app={d['appPkg']} activity={d['activity']} nodes={len(d['nodes'])}")
    wins = d["windows"]
    for n in d["nodes"]:
        w = wins[n["w"]]
        if "h" in n["flags"]:
            continue
        if w["pkg"] != d["appPkg"] and not show_all and w["type"] != 1:
            continue
        lab = n["label"] or n["hint"]
        if not show_all and not (lab or "C" in n["flags"] or "E" in n["flags"]):
            continue
        line = f'{n["flags"]:4s} {n["cls"][:14]:14s} {n["id"][:26]:26s} {lab[:60]!r:62s} {n["b"]}'
        if pat and not pat.search(line):
            continue
        print(line)


if __name__ == "__main__":
    main()

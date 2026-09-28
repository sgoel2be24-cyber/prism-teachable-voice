#!/usr/bin/env python3
"""Summarise runs from the phone's runs.jsonl started at or after a given epoch-ms.
   session_report.py <start_ms> [--md]"""
import json, subprocess, sys, statistics, os

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
raw = subprocess.run([ADB, "shell", "cat", "/sdcard/Android/data/com.prism.tva/files/runs.jsonl"],
                     capture_output=True, text=True).stdout
start = int(sys.argv[1])
runs = [r for r in (json.loads(l) for l in raw.splitlines() if l.strip()) if r.get("startedAt", 0) >= start]
md = "--md" in sys.argv

def methods(r):
    out = {}
    for s in r.get("steps", []):
        out[s["method"]] = out.get(s["method"], 0) + 1
    return out

if md:
    print("| # | Command | Outcome | Time | LLM calls | Step methods |")
    print("|---|---|---|---|---|---|")
for i, r in enumerate(runs, 1):
    m = ", ".join(f"{k}×{v}" for k, v in sorted(methods(r).items()))
    slots = ", ".join(f"{k}={v}" for k, v in r.get("slots", {}).items())
    if md:
        print(f"| {i} | {r['utterance']} | {r['outcome']} | {r['ms']/1000:.1f} s | {r['llmCalls']} | {m} |")
    else:
        print(f"{i}. {r['outcome']:8} {r['ms']/1000:5.1f}s llm={r['llmCalls']:2}  {r['utterance']}  [{slots}]  {m}  {r.get('reason','')}")

# Reaching the payment step and handing over is the intended end of a shopping flow.
ok = [r for r in runs if r["outcome"] == "success" or (r["outcome"] == "handover" and r.get("reason") == "payment")]
if runs:
    print()
    print(f"Success: {len(ok)}/{len(runs)}")
    if ok:
        print(f"Median time (successful): {statistics.median(r['ms'] for r in ok)/1000:.1f} s")
        print(f"Median LLM calls (successful): {statistics.median(r['llmCalls'] for r in ok)}")
        fast = sum(1 for r in ok for s in r['steps'] if s['method'] in ('match', 'scroll+match', 'type', 'launch'))
        total = sum(len(r['steps']) for r in ok)
        print(f"Steps done without the LLM: {fast}/{total}")

#!/usr/bin/env python3
"""Paraphrase-match accuracy: sends commands to the phone's matcher (no execution) and scores them.

usage: eval_match.py [cases.json]      default: the built-in Amazon set
A case passes when the kind is right (run/none/status), the flow's app is right, and every expected
slot value appears in the extracted value (case/punctuation-insensitive).
"""
import json, os, re, subprocess, sys, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
LOG = "/sdcard/Android/data/com.prism.tva/files/debug.log"

AMAZON = [
    # paraphrases / changed values -> Amazon flow
    {"u": "Search for wireless earbuds on Amazon and add the first result to cart", "app": "Amazon", "slots": {"product": "wireless earbuds"}},
    {"u": "search for phone case on amazon and add the first result to cart", "app": "Amazon", "slots": {"product": "phone case"}},
    {"u": "add wireless earbuds to my amazon cart", "app": "Amazon", "slots": {"product": "wireless earbuds"}},
    {"u": "put a phone cover in my amazon cart", "app": "Amazon", "slots": {"product": "phone cover"}},
    {"u": "find a usb c charger on amazon and add it to the cart", "app": "Amazon", "slots": {"product": "usb c charger"}},
    {"u": "amazon pe bluetooth speaker cart mein daal do", "app": "Amazon", "slots": {"product": "bluetooth speaker"}},
    {"u": "I need a laptop stand, add the top result on Amazon to my cart", "app": "Amazon", "slots": {"product": "laptop stand"}},
    {"u": "get me some AAA batteries on amazon", "app": "Amazon", "slots": {"product": "aaa batteries"}},
    {"u": "look up a yoga mat on amazon and put the first one in my cart", "app": "Amazon", "slots": {"product": "yoga mat"}},
    {"u": "add two packs of green tea to my amazon cart", "app": "Amazon", "slots": {"product": "green tea"}},
    {"u": "can you add a mouse pad on amazon", "app": "Amazon", "slots": {"product": "mouse pad"}},
    {"u": "shop for a steel water bottle on amazon", "app": "Amazon", "slots": {"product": "water bottle"}},
    # not learned -> offer to teach
    {"u": "book a cab to the airport", "kind": "none"},
    {"u": "send hi to mom on whatsapp", "kind": "none"},
    {"u": "set an alarm for 6 am", "kind": "none"},
    {"u": "play some lo-fi music on spotify", "kind": "none"},
    # status questions
    {"u": "did the last run succeed?", "kind": "status"},
    {"u": "did that work?", "kind": "status"},
]


def norm(s):
    return re.sub(r"[^a-z0-9 ]+", "", (s or "").lower()).strip()


def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout


def q(s):
    return "'" + s.replace("'", "'\\''") + "'"


def main():
    cases = json.load(open(sys.argv[1])) if len(sys.argv) > 1 else AMAZON
    run_tag = f"ev{int(time.time())}"
    for i, c in enumerate(cases):
        adb("shell", f"am broadcast -p com.prism.tva -a com.prism.tva.MATCH --es tag {run_tag}_{i} --es utterance {q(c['u'])}")
        time.sleep(2.6)
    time.sleep(4)
    results = {}
    for line in adb("shell", f"grep MATCHRESULT {LOG}").splitlines():
        m = re.search(r"MATCHRESULT (\{.*\})", line)
        if m:
            r = json.loads(m.group(1))
            if r.get("tag", "").startswith(run_tag):
                results[int(r["tag"].split("_")[-1])] = r
    ok = 0
    ms = []
    for i, c in enumerate(cases):
        r = results.get(i)
        kind = c.get("kind", "run")
        passed = False
        why = "no result"
        if r:
            ms.append(r["ms"])
            if r["kind"] != kind:
                why = f"kind {r['kind']} != {kind}"
            elif kind == "run" and norm(r.get("recipeApp")) != norm(c["app"]):
                why = f"app {r.get('recipeApp')} != {c['app']}"
            elif kind == "run" and not all(norm(v) in norm(r["slots"].get(k, "")) or norm(r["slots"].get(k, "")) in norm(v) and r["slots"].get(k) for k, v in c.get("slots", {}).items()):
                why = f"slots {r['slots']} vs {c.get('slots')}"
            else:
                passed, why = True, r.get("how", "")
        ok += passed
        print(f"{'PASS' if passed else 'FAIL'}  {c['u'][:60]:60s}  {why}")
    n = len(cases)
    print(f"\nMatch accuracy: {ok}/{n} = {100.0 * ok / n:.0f}%   median latency: {sorted(ms)[len(ms) // 2] if ms else '-'} ms")


if __name__ == "__main__":
    main()

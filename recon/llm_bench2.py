#!/usr/bin/env python3
"""Harder cases + reasoning-effort check for the two fastest models."""
import json, os, sys, time, urllib.request

KEY = open(os.path.expanduser("~/projects/prism-teachable-voice/.fireworks_key")).read().strip()
URL = "https://api.fireworks.ai/inference/v1/chat/completions"
sys.path.insert(0, os.path.dirname(__file__))
from llm_bench import MATCH_SYS, ACT_SYS  # noqa: E402

FLOWS = [
    {"id": "r1", "description": "Order a {item} pizza from {restaurant} on Zomato", "slots": ["item", "restaurant", "quantity", "address"],
     "examples": {"item": "margherita", "restaurant": "dominos", "quantity": "1", "address": "default"}},
    {"id": "r2", "description": "Search for {query} on Amazon and add the first result to cart", "slots": ["query"],
     "examples": {"query": "wireless earbuds"}},
]
MATCH_CASES = [
    "book a cab to the airport",
    "order a pizza",
    "did the last run succeed?",
    "put a phone cover in my amazon cart",
    "order margherita from dominos and deliver it to work",
]
POPUP = (
    "Goal: tap 'Add item' on the customisation sheet (step 5 of 7 in 'Order a margherita pizza from dominos').\n"
    "Screen (Zomato):\n[1] 'Movie voucher unlocked!' (text)\n[2] 'Get ₹125 OFF on booking movie tickets on the District app' (text)\n"
    "[3] 'Got it' (button)\n[4] 'Margherita Pizza' (text, dimmed behind dialog)\n[5] 'Add item ₹112' (button, behind dialog)"
)


def call(model, system, user, extra=None):
    body = {"model": model, "max_tokens": 400, "temperature": 0,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
    body.update(extra or {})
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers={
        "Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            d = json.load(r)
    except Exception as e:
        return time.time() - t0, f"ERROR {e}", {}
    return time.time() - t0, (d["choices"][0]["message"].get("content") or "").strip(), d.get("usage", {})


configs = [
    ("gpt-oss-120b low", "accounts/fireworks/models/gpt-oss-120b", {"reasoning_effort": "low"}),
    ("minimax-m3", "accounts/fireworks/models/minimax-m3", {}),
]
for label, model, extra in configs:
    for c in MATCH_CASES:
        dt, out, u = call(model, MATCH_SYS, json.dumps({"command": c, "flows": FLOWS}), extra)
        print(f"{label:17s} {dt:5.2f}s tok={u.get('completion_tokens')} | {c!r:48s} -> {out.replace(chr(10), ' ')[:150]}")
    dt, out, u = call(model, ACT_SYS, POPUP, extra)
    print(f"{label:17s} {dt:5.2f}s tok={u.get('completion_tokens')} | popup -> {out.replace(chr(10), ' ')[:160]}")
    sys.stdout.flush()

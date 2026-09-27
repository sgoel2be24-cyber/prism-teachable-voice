#!/usr/bin/env python3
"""Latency/quality check of Fireworks models on the assistant's two runtime prompts."""
import json, os, sys, time, urllib.request

KEY = open(os.path.expanduser("~/projects/prism-teachable-voice/.fireworks_key")).read().strip()
URL = "https://api.fireworks.ai/inference/v1/chat/completions"

MATCH_SYS = (
    "You map a user's voice command to one of the phone automations they taught earlier. "
    "Reply with JSON only: {\"flow\": <id or null>, \"slots\": {name: value}, \"missing\": [slot names the user did not give], "
    "\"confidence\": 0..1, \"status_query\": true|false}. Use null when no flow fits."
)
MATCH_USER = json.dumps({
    "command": "get me a farmhouse from dominos, two of them",
    "flows": [
        {"id": "r1", "description": "Order a {item} pizza from {restaurant} on Zomato", "slots": ["item", "restaurant", "quantity"],
         "examples": {"item": "margherita", "restaurant": "dominos", "quantity": "1"}},
        {"id": "r2", "description": "Search for {query} on Amazon and add the first result to cart", "slots": ["query"],
         "examples": {"query": "wireless earbuds"}},
    ],
})

ACT_SYS = (
    "You operate an Android app for the user. Given the goal and the numbered on-screen elements, choose ONE action. "
    "Reply JSON only: {\"action\": \"tap\"|\"type\"|\"scroll_down\"|\"back\"|\"done\"|\"ask\", \"element\": <number or null>, "
    "\"text\": <string or null>, \"question\": <string or null>, \"reason\": <short>}."
)
ACT_USER = (
    "Goal: add 2 x Margherita Pizza to the cart (the user taught this flow with quantity 1).\n"
    "Current step: 'Tap \"Add item\"' on the customisation sheet.\n"
    "Screen (Zomato, bottom sheet):\n"
    "[1] 'Margherita Pizza' (text)\n[2] 'Crust · Required · Select any 1 option' (text)\n"
    "[3] 'New Hand Tossed' (radio, checked)\n[4] 'Classic Hand Tossed' (radio)\n"
    "[5] id=button_remove '−' (button)\n[6] '1' (text, quantity)\n[7] id=button_add '+' (button)\n"
    "[8] 'Add item ₹112' (button)\n[9] close (button)"
)


def call(model, system, user, max_tokens=300):
    body = {"model": model, "max_tokens": max_tokens, "temperature": 0,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers={
        "Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            d = json.load(r)
    except Exception as e:
        return time.time() - t0, f"ERROR {e}", None
    msg = d["choices"][0]["message"]
    return time.time() - t0, (msg.get("content") or "").strip(), d.get("usage", {})


models = sys.argv[1:] or [
    "accounts/fireworks/models/gpt-oss-120b",
    "accounts/fireworks/models/deepseek-v4-flash-0731",
    "accounts/fireworks/models/deepseek-v4p1-flash",
    "accounts/fireworks/models/glm-5p3-flash",
    "accounts/fireworks/routers/glm-5p3-fast",
    "accounts/fireworks/routers/kimi-k3-fast",
    "accounts/fireworks/models/qwen3p7-plus",
    "accounts/fireworks/models/minimax-m3",
]
for m in models:
    for name, sysm, user in [("match", MATCH_SYS, MATCH_USER), ("act", ACT_SYS, ACT_USER)]:
        dt, out, usage = call(m, sysm, user)
        short = out.replace("\n", " ")[:170]
        print(f"{m.split('/')[-1]:26s} {name:5s} {dt:5.2f}s tok={usage.get('completion_tokens') if usage else '-'} | {short}")
    sys.stdout.flush()

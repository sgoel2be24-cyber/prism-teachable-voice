#!/usr/bin/env python3
"""Print a spike DUMP (all windows) as an indented tree, optionally only the subtree containing a label."""
import json, sys
data = json.load(open(sys.argv[1]))
needle = sys.argv[2].lower() if len(sys.argv) > 2 else None
maxdepth = int(sys.argv[3]) if len(sys.argv) > 3 else 99
def lab(n): return (n.get("text") or n.get("desc") or "")
def contains(n):
    return needle in lab(n).lower() or any(contains(c) for c in n.get("children", []))
def show(n, d, base):
    if d - base > maxdepth: return
    idv = (n.get("id") or "").split("/")[-1]
    fl = ("C" if n.get("clickable") else "") + ("E" if n.get("editable") else "") + ("" if n.get("visible") else "h")
    print("  " * (d - base) + f"[{fl}] {n.get('cls','').split('.')[-1]} id={idv} {lab(n)[:50]!r} {n.get('bounds')}")
    for c in n.get("children", []): show(c, d + 1, base)
def find(n, d):
    kids = [c for c in n.get("children", []) if contains(c)]
    if needle in lab(n).lower() or len(kids) != 1: return n, d
    return find(kids[0], d + 1)
for w in data:
    t = w["tree"]
    print(f"== window type={w['windowType']} layer={w['layer']} pkg={w.get('pkg')} title={w.get('title')}")
    if needle:
        if not contains(t): continue
        # climb to the smallest subtree whose root holds several labelled things incl. the needle
        n, d = find(t, 0)
        show(n, d, d)
    else:
        show(t, 0, 0)

#!/usr/bin/env python3
"""Summarise uiautomator dumps: per-screen stats, or a compact element list for one screen."""
import sys, glob, os, re
import xml.etree.ElementTree as ET

def nodes(path):
    try:
        return list(ET.parse(path).getroot().iter('node'))
    except ET.ParseError:
        return []

def label(n):
    t = n.get('text') or ''
    d = n.get('content-desc') or ''
    return (t or d).replace('\n', ' ')[:60]

def stats(folder):
    for f in sorted(glob.glob(os.path.join(folder, '[0-9]*.xml'))):
        ns = nodes(f)
        rid = sum(1 for n in ns if n.get('resource-id'))
        lab = sum(1 for n in ns if label(n))
        clk = sum(1 for n in ns if n.get('clickable') == 'true')
        pk = {n.get('package') for n in ns}
        pk.discard(None)
        tops = [label(n) for n in ns if label(n)][:6]
        print(f"{os.path.basename(f)[:3]} nodes={len(ns):4d} ids={rid:4d} labels={lab:4d} clickable={clk:3d} pkg={','.join(sorted(pk))[:30]} | {' · '.join(tops)[:150]}")

def detail(path, only_useful=True):
    for n in nodes(path):
        rid = (n.get('resource-id') or '').split('/')[-1]
        lab = label(n)
        clk = n.get('clickable') == 'true'
        cls = (n.get('class') or '').split('.')[-1]
        if only_useful and not (lab or clk or n.get('class','').endswith('EditText')):
            continue
        flags = ''.join(c for c, on in [('C', clk), ('S', n.get('scrollable') == 'true'), ('E', cls == 'EditText'), ('P', n.get('password') == 'true')] if on)
        print(f"{flags:3s} {cls[:16]:16s} id={rid[:32]:32s} {lab!r:64s} {n.get('bounds')}")

if __name__ == '__main__':
    if len(sys.argv) == 2 and os.path.isdir(sys.argv[1]):
        stats(sys.argv[1])
    else:
        detail(sys.argv[1])

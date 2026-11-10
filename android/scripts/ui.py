#!/usr/bin/env python3
"""Looks in a `uiautomator dump` for a node whose text or description is the
label (with --has: contains it). Prints the node's centre; exit 1 if none.
With --texts and no label: prints every text on the screen, exit 2 if the dump
holds none (it failed), so an "is not there" check can't pass on a dump that
never happened."""
import re
import sys
import xml.etree.ElementTree as ET

args = [a for a in sys.argv[1:] if not a.startswith("--")]
try:
    nodes = list(ET.parse(args[0]).iter("node"))
except (ET.ParseError, OSError):
    nodes = []
texts = [v for n in nodes for v in (n.get("text") or "", n.get("content-desc") or "") if v.strip()]
if not texts:
    sys.exit(2)
if "--texts" in sys.argv:
    print(" | ".join(t.replace("\n", " / ") for t in texts)[:4000])
    sys.exit(0)
label = args[1]
for node in nodes:
    for value in (node.get("text") or "", node.get("content-desc") or ""):
        if value == label or ("--has" in sys.argv and label in value):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)

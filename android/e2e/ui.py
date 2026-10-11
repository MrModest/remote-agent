#!/usr/bin/env python3
"""Finds a view in a `uiautomator dump` and prints the centre of its bounds.

    adb exec-out uiautomator dump /dev/tty > ui.xml
    python3 -I android/e2e/ui.py ui.xml 'text=Pair'          # exact text
    python3 -I android/e2e/ui.py ui.xml 'desc=Send'          # content description
    python3 -I android/e2e/ui.py ui.xml 'id=pairing-link'    # Compose test tag
    python3 -I android/e2e/ui.py ui.xml 'contains=Echo'      # text containing

Prints "x y" for the first match (or the Nth with a trailing #N), exit 1 if none.
With --all, prints every match's text, one per line.
"""
import re
import sys
import xml.etree.ElementTree as ET


def matches(node, kind, value):
    text = node.get("text", "")
    desc = node.get("content-desc", "")
    rid = node.get("resource-id", "")
    if kind == "text":
        return text == value or desc == value
    if kind == "desc":
        return desc == value
    if kind == "id":
        return rid == value or rid.endswith(":id/" + value)
    if kind == "contains":
        return value in text or value in desc
    raise SystemExit(f"unknown selector {kind}")


def main():
    args = [a for a in sys.argv[1:] if a != "--all"]
    show_all = "--all" in sys.argv
    path, selector = args[0], args[1]
    index = 0
    if "#" in selector:
        selector, n = selector.rsplit("#", 1)
        index = int(n)
    kind, value = selector.split("=", 1)
    raw = open(path, encoding="utf-8", errors="replace").read()
    raw = raw[raw.find("<?xml"):raw.rfind(">") + 1]
    root = ET.fromstring(raw)
    found = [n for n in root.iter("node") if matches(n, kind, value)]
    if show_all:
        for n in found:
            print(n.get("text") or n.get("content-desc"))
        return
    if len(found) <= index:
        sys.exit(1)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", found[index].get("bounds")))
    print((x1 + x2) // 2, (y1 + y2) // 2)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Print a safe Chromium viewport for an SVG."""
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()

def number(value):
    if not value:
        return None
    match = re.match(r"\s*([0-9]+(?:\.[0-9]+)?)", value)
    return float(match.group(1)) if match else None

width = number(root.get("width"))
height = number(root.get("height"))
if not width or not height:
    viewbox = root.get("viewBox", "").replace(",", " ").split()
    if len(viewbox) == 4:
        width, height = float(viewbox[2]), float(viewbox[3])
if not width or not height:
    raise SystemExit("SVG has no usable width/height or viewBox")
width = max(64, min(8192, int(width + 0.999)))
height = max(64, min(8192, int(height + 0.999)))
print(width, height)

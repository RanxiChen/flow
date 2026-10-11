#!/usr/bin/env python3
"""Render common Excalidraw elements to a deterministic QA SVG."""
from __future__ import annotations
import argparse
import html
import json
import math
import sys
from pathlib import Path
from typing import Any

def esc(value: Any) -> str:
    return html.escape(str(value), quote=True)

def num(value: Any, default: float = 0.0) -> float:
    return float(value) if isinstance(value, (int, float)) and math.isfinite(value) else default

def attrs(element: dict[str, Any]) -> str:
    stroke = element.get("strokeColor") or "#1b1b1f"
    fill = element.get("backgroundColor") or "transparent"
    fill = "none" if fill == "transparent" else fill
    opacity = max(0.0, min(1.0, num(element.get("opacity"), 100) / 100))
    width = max(0.5, num(element.get("strokeWidth"), 1.5))
    dash = ' stroke-dasharray="10 7"' if element.get("strokeStyle") == "dashed" else ""
    return f'stroke="{esc(stroke)}" fill="{esc(fill)}" stroke-width="{width:g}" opacity="{opacity:g}"{dash}'

def rotation(element: dict[str, Any], cx: float, cy: float) -> str:
    angle = num(element.get("angle"))
    return "" if abs(angle) < 1e-9 else f' transform="rotate({angle * 180 / math.pi:g} {cx:g} {cy:g})"'

def render(data: dict[str, Any], padding: float) -> str:
    elements = [e for e in data.get("elements", []) if isinstance(e, dict) and not e.get("isDeleted")]
    if not elements:
        raise ValueError("no visible elements")
    min_x = min(num(e.get("x")) for e in elements)
    min_y = min(num(e.get("y")) for e in elements)
    max_x = max(num(e.get("x")) + max(1, num(e.get("width"))) for e in elements)
    max_y = max(num(e.get("y")) + max(1, num(e.get("height"))) for e in elements)
    width, height = max_x - min_x + 2 * padding, max_y - min_y + 2 * padding
    vx, vy = min_x - padding, min_y - padding
    bg = (data.get("appState") or {}).get("viewBackgroundColor") or "#ffffff"
    defs: list[str] = []
    body = [f'<rect x="{vx:g}" y="{vy:g}" width="{width:g}" height="{height:g}" fill="{esc(bg)}"/>']
    unsupported: set[str] = set()

    for e in elements:
        kind = e.get("type")
        x, y = num(e.get("x")), num(e.get("y"))
        w, h = max(0, num(e.get("width"))), max(0, num(e.get("height")))
        cx, cy = x + w / 2, y + h / 2
        rot, common = rotation(e, cx, cy), attrs(e)
        if kind in {"rectangle", "frame", "embeddable"}:
            radius = min(12, w / 8, h / 8) if e.get("roundness") else 0
            body.append(f'<rect x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}" rx="{radius:g}" {common}{rot}/>')
        elif kind == "ellipse":
            body.append(f'<ellipse cx="{cx:g}" cy="{cy:g}" rx="{w/2:g}" ry="{h/2:g}" {common}{rot}/>')
        elif kind == "diamond":
            pts = f"{cx:g},{y:g} {x+w:g},{cy:g} {cx:g},{y+h:g} {x:g},{cy:g}"
            body.append(f'<polygon points="{pts}" {common}{rot}/>')
        elif kind in {"line", "arrow", "freedraw"}:
            points = [(x + num(p[0]), y + num(p[1])) for p in e.get("points", []) if isinstance(p, list) and len(p) >= 2]
            if len(points) < 2:
                continue
            path = "M " + " L ".join(f"{px:g} {py:g}" for px, py in points)
            markers = ""
            if kind == "arrow":
                color = e.get("strokeColor") or "#1b1b1f"
                marker_id = "end-" + str(e.get("id", len(defs))).replace(" ", "-")
                defs.append(f'<marker id="{esc(marker_id)}" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto" markerUnits="strokeWidth"><path d="M0 0 L10 5 L0 10 z" fill="{esc(color)}"/></marker>')
                markers = f' marker-end="url(#{esc(marker_id)})"'
            body.append(f'<path d="{path}" {common}{markers}{rot}/>')
        elif kind == "text":
            text = str(e.get("text") or e.get("originalText") or "")
            size = max(8, num(e.get("fontSize"), 20))
            align = e.get("textAlign") or "left"
            anchor = {"left": "start", "center": "middle", "right": "end"}.get(align, "start")
            tx = x if anchor == "start" else cx if anchor == "middle" else x + w
            lines = text.splitlines() or [""]
            line_height = size * num(e.get("lineHeight"), 1.25)
            vertical = e.get("verticalAlign") or "top"
            total = line_height * len(lines)
            ty = y + size if vertical == "top" else y + (h - total) / 2 + size if vertical == "middle" else y + h - total + size
            spans = "".join(f'<tspan x="{tx:g}" dy="{0 if i == 0 else line_height:g}">{esc(line)}</tspan>' for i, line in enumerate(lines))
            body.append(f'<text x="{tx:g}" y="{ty:g}" fill="{esc(e.get("strokeColor") or "#1b1b1f")}" font-family="Arial, Noto Sans CJK SC, sans-serif" font-size="{size:g}" text-anchor="{anchor}"{rot}>{spans}</text>')
        else:
            unsupported.add(str(kind))
            body.append(f'<rect x="{x:g}" y="{y:g}" width="{max(w,60):g}" height="{max(h,30):g}" fill="#fff3bf" stroke="#f08c00" stroke-dasharray="6 4"{rot}/>')
    if unsupported:
        print("WARNING: simplified preview for: " + ", ".join(sorted(unsupported)), file=sys.stderr)
    return f'<?xml version="1.0" encoding="UTF-8"?>\n<svg xmlns="http://www.w3.org/2000/svg" width="{math.ceil(width)}" height="{math.ceil(height)}" viewBox="{vx:g} {vy:g} {width:g} {height:g}"><defs>{"".join(defs)}</defs>{"".join(body)}</svg>\n'

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output_svg", type=Path)
    parser.add_argument("--padding", type=float, default=32)
    args = parser.parse_args()
    try:
        data = json.loads(args.input.read_text(encoding="utf-8"))
        args.output_svg.write_text(render(data, max(0, args.padding)), encoding="utf-8")
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(f"PASS svg={args.output_svg} bytes={args.output_svg.stat().st_size}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())

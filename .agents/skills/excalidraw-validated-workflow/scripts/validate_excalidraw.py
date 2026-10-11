#!/usr/bin/env python3
"""Validate the structural integrity of a native Excalidraw JSON file."""
from __future__ import annotations
import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any

def finite(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--strict-bindings", action="store_true")
    args = parser.parse_args()
    try:
        data = json.loads(args.input.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        print(f"ERROR: invalid UTF-8 JSON: {exc}", file=sys.stderr)
        return 1

    errors: list[str] = []
    if not isinstance(data, dict):
        errors.append("top level must be an object")
        data = {}
    if data.get("type") != "excalidraw":
        errors.append('top-level type must be "excalidraw"')
    elements = data.get("elements")
    if not isinstance(elements, list) or not elements:
        errors.append("elements must be a non-empty array")
        elements = []

    by_id: dict[str, dict[str, Any]] = {}
    duplicate_ids: set[str] = set()
    arrows = 0
    texts = 0
    for i, element in enumerate(elements):
        owner = f"elements[{i}]"
        if not isinstance(element, dict):
            errors.append(f"{owner} must be an object")
            continue
        element_id = element.get("id")
        if not isinstance(element_id, str) or not element_id:
            errors.append(f"{owner}.id must be a non-empty string")
        elif element_id in by_id:
            duplicate_ids.add(element_id)
        else:
            by_id[element_id] = element
        if element.get("type") == "arrow":
            arrows += 1
        if element.get("type") == "text":
            texts += 1
        for key in ("x", "y", "width", "height", "angle"):
            if not finite(element.get(key)):
                errors.append(f"{owner}.{key} must be finite")
        if element.get("type") in {"arrow", "line", "freedraw"}:
            points = element.get("points")
            if not isinstance(points, list) or len(points) < 2:
                errors.append(f"{owner}.points must contain at least two points")
            else:
                for j, point in enumerate(points):
                    if not isinstance(point, list) or len(point) < 2 or not finite(point[0]) or not finite(point[1]):
                        errors.append(f"{owner}.points[{j}] must be [finite x, finite y]")

    if duplicate_ids:
        errors.append("duplicate IDs: " + ", ".join(sorted(duplicate_ids)))

    def reference(owner: str, key: str, value: Any) -> None:
        if value is None:
            return
        if not isinstance(value, str) or not value:
            errors.append(f"{owner}.{key} must be null or a non-empty string")
        elif value not in by_id:
            errors.append(f"{owner}.{key} references missing element {value!r}")

    for i, element in enumerate(elements):
        if not isinstance(element, dict):
            continue
        owner = f"elements[{i}]({element.get('id', '?')})"
        for key in ("containerId", "frameId"):
            reference(owner, key, element.get(key))
        bound = element.get("boundElements")
        if bound is not None:
            if not isinstance(bound, list):
                errors.append(f"{owner}.boundElements must be null or an array")
            else:
                for j, item in enumerate(bound):
                    if not isinstance(item, dict):
                        errors.append(f"{owner}.boundElements[{j}] must be an object")
                    else:
                        reference(f"{owner}.boundElements[{j}]", "id", item.get("id"))
        if element.get("type") == "arrow":
            bindings = []
            for key in ("startBinding", "endBinding"):
                binding = element.get(key)
                bindings.append(binding)
                if binding is not None:
                    if not isinstance(binding, dict):
                        errors.append(f"{owner}.{key} must be null or an object")
                    else:
                        reference(f"{owner}.{key}", "elementId", binding.get("elementId"))
            bound_sides = sum(item is not None for item in bindings)
            if bound_sides == 1:
                errors.append(f"{owner} has only one bound endpoint")
            if args.strict_bindings and bound_sides != 2:
                errors.append(f"{owner} must have two bound endpoints in strict mode")

    files = data.get("files", {})
    if not isinstance(files, dict):
        errors.append("files must be an object")
        files = {}
    for element in elements:
        if isinstance(element, dict) and element.get("type") == "image":
            if element.get("fileId") not in files:
                errors.append(f"image {element.get('id', '?')!r} references missing file")

    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    status = "FAIL" if errors else "PASS"
    print(f"{status} elements={len(elements)} arrows={arrows} text={texts} files={len(files)} errors={len(errors)}")
    return 1 if errors else 0

if __name__ == "__main__":
    raise SystemExit(main())

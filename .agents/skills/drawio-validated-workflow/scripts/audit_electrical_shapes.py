#!/usr/bin/env python3
"""Audit known Draw.io electrical stencil semantics and aspect ratios."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


KNOWN = {
    "mxgraph.electrical.resistors.resistor_1": {
        "name": "IEC rectangular resistor",
        "ratio": 5.0,
    },
    "mxgraph.electrical.resistors.resistor_2": {
        "name": "ANSI zigzag resistor",
        "ratio": 5.0,
    },
    "mxgraph.electrical.signal_sources.current_source": {
        "name": "ambiguous current stencil",
        "ratio": 40.0 / 60.0,
    },
    "mxgraph.electrical.signal_sources.dc_source_1": {
        "name": "DC voltage source with polarity",
        "ratio": 70.0 / 75.0,
    },
}


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def shape_from_style(style: str) -> str | None:
    for item in style.split(";"):
        key, separator, value = item.partition("=")
        if separator and key.strip() == "shape":
            return value.strip()
    return None


def style_value(style: str, wanted: str) -> str | None:
    for item in style.split(";"):
        key, separator, value = item.partition("=")
        if separator and key.strip() == wanted:
            return value.strip()
    return None


def style_number(style: str, wanted: str) -> float | None:
    value = style_value(style, wanted)
    if value is None:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def geometry(cell: ET.Element) -> tuple[float | None, float | None]:
    for child in cell:
        if local_name(child.tag) == "mxGeometry":
            try:
                return float(child.get("width", "")), float(child.get("height", ""))
            except ValueError:
                return None, None
    return None, None


def object_labels(root: ET.Element) -> dict[str, str]:
    labels: dict[str, str] = {}
    for element in root.iter():
        if local_name(element.tag) != "object":
            continue
        label = element.get("label") or ""
        for child in element.iter():
            if local_name(child.tag) == "mxCell" and child.get("id"):
                labels[child.get("id", "")] = label
    return labels


def issue(
    issues: list[dict[str, Any]],
    severity: str,
    cell_id: str,
    shape: str,
    message: str,
) -> None:
    issues.append(
        {
            "severity": severity,
            "cell_id": cell_id,
            "shape": shape,
            "message": message,
        }
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument(
        "--resistor-standard",
        choices=("any", "iec", "ansi"),
        default="any",
    )
    parser.add_argument(
        "--strict-current-source",
        action="store_true",
        help="reject the known ambiguous current_source stencil",
    )
    parser.add_argument(
        "--strict-voltage-source",
        action="store_true",
        help="reject directly connected dc_source_1 stencils with inset visible terminals",
    )
    parser.add_argument("--aspect-tolerance", type=float, default=0.15)
    args = parser.parse_args()

    try:
        path = args.input.expanduser().resolve(strict=True)
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}), file=sys.stderr)
        return 1

    issues: list[dict[str, Any]] = []
    components: list[dict[str, Any]] = []
    labels = object_labels(root)
    cells = [cell for cell in root.iter() if local_name(cell.tag) == "mxCell"]
    edges = [cell for cell in cells if cell.get("edge") == "1"]
    polarity_marks: dict[str, set[str]] = {}
    for cell in cells:
        component_id = cell.get("component")
        mark = cell.get("polarityMark")
        if component_id and mark in {"+", "-", "−"}:
            polarity_marks.setdefault(component_id, set()).add("-" if mark == "−" else mark)

    for cell in cells:
        if local_name(cell.tag) != "mxCell" or cell.get("vertex") != "1":
            continue
        style = cell.get("style") or ""
        shape = shape_from_style(style)
        composed_type = cell.get("electricalType")
        if composed_type == "voltage_source":
            cell_id = cell.get("id") or "<unknown>"
            label = cell.get("value") or labels.get(cell_id, "")
            width, height = geometry(cell)
            components.append(
                {
                    "cell_id": cell_id,
                    "label": label,
                    "shape": "composed.voltage_source",
                    "width": width,
                    "height": height,
                }
            )
            if "ellipse" not in style.split(";") and shape != "ellipse":
                issue(
                    issues,
                    "ERROR",
                    cell_id,
                    "composed.voltage_source",
                    "composed voltage source body must be an ellipse",
                )
            if cell.get("polarity") not in {
                "positive-top",
                "positive-bottom",
                "positive-left",
                "positive-right",
            }:
                issue(
                    issues,
                    "ERROR",
                    cell_id,
                    "composed.voltage_source",
                    "composed voltage source needs an explicit polarity orientation",
                )
            if polarity_marks.get(cell_id, set()) != {"+", "-"}:
                issue(
                    issues,
                    "ERROR",
                    cell_id,
                    "composed.voltage_source",
                    "composed voltage source needs visible + and - mark cells",
                )
            incident = [
                edge
                for edge in edges
                if edge.get("source") == cell_id or edge.get("target") == cell_id
            ]
            if len(incident) < 2:
                issue(
                    issues,
                    "ERROR",
                    cell_id,
                    "composed.voltage_source",
                    "composed voltage source needs at least two native incident edges",
                )
            for edge in incident:
                edge_style = edge.get("style") or ""
                if edge.get("source") == cell_id and style_value(edge_style, "exitPerimeter") == "0":
                    issue(
                        issues,
                        "ERROR",
                        cell_id,
                        "composed.voltage_source",
                        f"edge {edge.get('id')} disables source perimeter contact",
                    )
                if edge.get("target") == cell_id and style_value(edge_style, "entryPerimeter") == "0":
                    issue(
                        issues,
                        "ERROR",
                        cell_id,
                        "composed.voltage_source",
                        f"edge {edge.get('id')} disables target perimeter contact",
                    )
            continue
        if not shape or not shape.startswith("mxgraph.electrical."):
            continue
        cell_id = cell.get("id") or "<unknown>"
        label = cell.get("value") or labels.get(cell_id, "")
        width, height = geometry(cell)
        components.append(
            {
                "cell_id": cell_id,
                "label": label,
                "shape": shape,
                "width": width,
                "height": height,
            }
        )

        known = KNOWN.get(shape)
        if known and width and height and height > 0:
            actual_ratio = width / height
            expected_ratio = float(known["ratio"])
            relative_error = abs(actual_ratio - expected_ratio) / expected_ratio
            if relative_error > args.aspect_tolerance:
                issue(
                    issues,
                    "ERROR",
                    cell_id,
                    shape,
                    f"aspect ratio {actual_ratio:.3f} distorts native ratio "
                    f"{expected_ratio:.3f}",
                )

        label_lower = label.lower()
        if shape.endswith(".resistor_1"):
            if args.resistor_standard == "ansi":
                issue(issues, "ERROR", cell_id, shape, "IEC rectangular resistor used in ANSI mode")
            if any(word in label_lower for word in ("zigzag", "ansi", "锯齿", "美式")):
                issue(issues, "ERROR", cell_id, shape, "label requests zigzag/ANSI but resistor_1 is IEC rectangular")
        elif shape.endswith(".resistor_2"):
            if args.resistor_standard == "iec":
                issue(issues, "ERROR", cell_id, shape, "ANSI zigzag resistor used in IEC mode")
            if any(word in label_lower for word in ("rectangular", "iec", "矩形", "欧式")):
                issue(issues, "ERROR", cell_id, shape, "label requests rectangular/IEC but resistor_2 is ANSI zigzag")
        if shape.endswith((".resistor_1", ".resistor_2")):
            incident_ports: list[str] = []
            port_edges: list[tuple[ET.Element, str, str]] = []
            for edge in edges:
                if edge.get("source") == cell_id and edge.get("sourcePort"):
                    incident_ports.append(edge.get("sourcePort", ""))
                    port_edges.append((edge, edge.get("sourcePort", ""), "exit"))
                if edge.get("target") == cell_id and edge.get("targetPort"):
                    incident_ports.append(edge.get("targetPort", ""))
                    port_edges.append((edge, edge.get("targetPort", ""), "entry"))
            try:
                rotation = float(style_value(style, "rotation") or "0") % 360
            except ValueError:
                rotation = 0
            vertical = rotation in {90.0, 270.0}
            if any(port in {"top", "bottom"} for port in incident_ports) and not vertical:
                issue(
                    issues,
                    "ERROR",
                    cell_id,
                    shape,
                    "top/bottom resistor ports require rotation=90 or rotation=270 "
                    "so wires meet the visible terminals",
                )
            if any(port in {"left", "right"} for port in incident_ports) and vertical:
                issue(
                    issues,
                    "ERROR",
                    cell_id,
                    shape,
                    "left/right resistor ports require an unrotated horizontal symbol "
                    "so wires meet the visible terminals",
                )
            expected_anchors: dict[str, tuple[float, float]] = {}
            if rotation == 90.0:
                expected_anchors = {"top": (0.0, 0.5), "bottom": (1.0, 0.5)}
            elif rotation == 270.0:
                expected_anchors = {"top": (1.0, 0.5), "bottom": (0.0, 0.5)}
            elif rotation in {0.0, 180.0}:
                expected_anchors = {"left": (0.0, 0.5), "right": (1.0, 0.5)}
                if rotation == 180.0:
                    expected_anchors = {"left": (1.0, 0.5), "right": (0.0, 0.5)}
            for edge, port, prefix in port_edges:
                expected = expected_anchors.get(port)
                if expected is None:
                    continue
                edge_style = edge.get("style") or ""
                actual = (
                    style_number(edge_style, f"{prefix}X"),
                    style_number(edge_style, f"{prefix}Y"),
                )
                if (
                    actual[0] is None
                    or actual[1] is None
                    or abs(actual[0] - expected[0]) > 0.001
                    or abs(actual[1] - expected[1]) > 0.001
                ):
                    issue(
                        issues,
                        "ERROR",
                        cell_id,
                        shape,
                        f"edge {edge.get('id')} port {port} at rotation={rotation:g} "
                        f"requires {prefix}X={expected[0]:g},"
                        f"{prefix}Y={expected[1]:g} before rotation",
                    )
        elif shape.endswith(".current_source"):
            severity = "ERROR" if args.strict_current_source else "WARNING"
            issue(
                issues,
                severity,
                cell_id,
                shape,
                "Draw.io 30.0.4 rendered this as overlapping circles without a "
                "conventional internal current arrow; visually revalidate or "
                "compose a circle-plus-arrow source",
            )
        elif shape.endswith(".dc_source_1"):
            incident = [
                edge
                for edge in edges
                if edge.get("source") == cell_id or edge.get("target") == cell_id
            ]
            if incident:
                severity = "ERROR" if args.strict_voltage_source else "WARNING"
                issue(
                    issues,
                    severity,
                    cell_id,
                    shape,
                    "Draw.io 30.0.4 renders the visible source circle inset from "
                    "the stencil bounding box, so direct native edges can stop "
                    "short of the visible terminals; compose an ellipse source "
                    "with explicit polarity marks and perimeter-connected edges",
                )

    errors = sum(item["severity"] == "ERROR" for item in issues)
    warnings = sum(item["severity"] == "WARNING" for item in issues)
    status = "FAIL" if errors else "PASS_WITH_WARNINGS" if warnings else "PASS"
    result = {
        "status": status,
        "input": str(path),
        "resistor_standard": args.resistor_standard,
        "strict_current_source": args.strict_current_source,
        "strict_voltage_source": args.strict_voltage_source,
        "electrical_components": components,
        "issues": issues,
        "errors": errors,
        "warnings": warnings,
    }
    stream = sys.stderr if errors else sys.stdout
    print(json.dumps(result, ensure_ascii=False), file=stream)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())

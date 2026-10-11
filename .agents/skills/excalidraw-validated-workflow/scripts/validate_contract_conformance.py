#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


def fail(message: str) -> None:
    raise ValueError(message)


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        fail(f"{path} top level must be an object")
    return data


def rule_ids(standards: dict[str, Any]) -> set[str]:
    result: set[str] = set()
    for key in ("semantic_rules", "visual_rules", "backend_constraints"):
        for item in standards.get(key, []):
            if isinstance(item, dict) and isinstance(item.get("id"), str):
                result.add(item["id"])
    return result


def expected_items(contract: dict[str, Any], name: str) -> list[dict[str, Any]]:
    values = contract.get(name)
    if not isinstance(values, list):
        fail(f"contract {name} must be an array")
    if any(not isinstance(item, dict) for item in values):
        fail(f"contract {name} must contain objects")
    return values


def validate_drawio(contract: dict[str, Any], artifact: Path) -> dict[str, Any]:
    document = ET.fromstring(artifact.read_text(encoding="utf-8"))
    cells = [item for item in document.iter() if item.tag.rsplit("}", 1)[-1] == "mxCell"]
    by_id = {item.get("id"): item for item in cells if item.get("id")}

    nodes = expected_items(contract, "nodes")
    edges = expected_items(contract, "edges")
    missing_nodes = [item["id"] for item in nodes if item.get("id") not in by_id]
    missing_edges = [item["id"] for item in edges if item.get("id") not in by_id]
    if missing_nodes:
        fail("artifact is missing contract nodes: " + ", ".join(missing_nodes))
    if missing_edges:
        fail("artifact is missing contract edges: " + ", ".join(missing_edges))

    actual_endpoints: set[tuple[str, str, str]] = set()
    for expected in edges:
        edge_id = expected["id"]
        cell = by_id[edge_id]
        if cell.get("edge") != "1":
            fail(f"contract edge {edge_id} is not a native Draw.io edge")
        for key in ("source", "target"):
            value = expected.get(key)
            if not isinstance(value, str) or not value:
                fail(f"contract edge {edge_id} lacks {key}")
            if cell.get(key) != value:
                fail(f"edge {edge_id} {key}={cell.get(key)!r}, expected {value!r}")
        expected_net = expected.get("net_id")
        if expected_net is not None and cell.get("net") != expected_net:
            fail(f"edge {edge_id} net={cell.get('net')!r}, expected {expected_net!r}")
        for side in ("source", "target"):
            expected_port = expected.get(f"{side}_port")
            actual_port = cell.get(f"{side}Port")
            if expected_port is not None and actual_port != expected_port:
                fail(
                    f"edge {edge_id} {side}Port={actual_port!r}, "
                    f"expected {expected_port!r}"
                )
            if expected_net and actual_port:
                actual_endpoints.add((expected_net, cell.get(side, ""), actual_port))

    diagram_type = str(contract.get("diagram_type", "")).lower()
    electrical = "electrical" in diagram_type or "circuit" in diagram_type
    if electrical:
        native_edges = [cell for cell in cells if cell.get("edge") == "1"]
        if not native_edges:
            fail("electrical diagram has zero native edges")
        fake_wires = [
            cell_id
            for cell_id, cell in by_id.items()
            if cell.get("vertex") == "1"
            and str(cell_id).lower().startswith(("wire_", "rail_"))
        ]
        if fake_wires:
            fail("electrical conductors cannot be vertices: " + ", ".join(fake_wires))

        domain = contract.get("domain_rules", {}).get("electrical", {})
        nets = domain.get("nets", [])
        for net in nets:
            net_id = net["id"]
            for pin in net["pins"]:
                component, port = pin.rsplit(".", 1)
                if (net_id, component, port) not in actual_endpoints:
                    fail(f"electrical pin {pin} is not connected to net {net_id}")

    return {
        "backend": "drawio",
        "nodes": len(nodes),
        "edges": len(edges),
        "electrical": electrical,
    }


def validate_excalidraw(contract: dict[str, Any], artifact: Path) -> dict[str, Any]:
    data = load_json(artifact)
    elements = data.get("elements")
    if not isinstance(elements, list):
        fail("Excalidraw elements must be an array")
    by_id = {
        item.get("id"): item
        for item in elements
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }
    nodes = expected_items(contract, "nodes")
    edges = expected_items(contract, "edges")
    missing_nodes = [item["id"] for item in nodes if item.get("id") not in by_id]
    missing_edges = [item["id"] for item in edges if item.get("id") not in by_id]
    if missing_nodes:
        fail("artifact is missing contract nodes: " + ", ".join(missing_nodes))
    if missing_edges:
        fail("artifact is missing contract edges: " + ", ".join(missing_edges))
    for expected in edges:
        edge_id = expected["id"]
        element = by_id[edge_id]
        if element.get("type") not in {"arrow", "line"}:
            fail(f"contract edge {edge_id} is not an Excalidraw arrow or line")
        source = expected.get("source")
        target = expected.get("target")
        start = element.get("startBinding")
        end = element.get("endBinding")
        if source and (not isinstance(start, dict) or start.get("elementId") != source):
            fail(f"edge {edge_id} start binding does not match {source}")
        if target and (not isinstance(end, dict) or end.get("elementId") != target):
            fail(f"edge {edge_id} end binding does not match {target}")
    return {"backend": "excalidraw", "nodes": len(nodes), "edges": len(edges)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("standards", type=Path)
    parser.add_argument("contract", type=Path)
    parser.add_argument("artifact", type=Path)
    args = parser.parse_args()
    try:
        standards = load_json(args.standards)
        contract = load_json(args.contract)
        applied = contract.get("applied_standard_rules")
        if not isinstance(applied, list):
            fail("contract applied_standard_rules must be an array")
        unknown = sorted(set(applied) - rule_ids(standards))
        if unknown:
            fail("contract applies unknown standard rules: " + ", ".join(unknown))
        if Path(contract.get("standards_file", "")).name != args.standards.name:
            fail("contract standards_file does not name the supplied standards file")

        suffix = args.artifact.suffix.lower()
        if suffix == ".drawio":
            result = validate_drawio(contract, args.artifact)
        elif suffix == ".excalidraw":
            result = validate_excalidraw(contract, args.artifact)
        else:
            fail("artifact must end in .drawio or .excalidraw")
        print(json.dumps({"status": "PASS", **result}, ensure_ascii=False))
        return 0
    except (OSError, json.JSONDecodeError, ET.ParseError, ValueError) as exc:
        print(json.dumps({"status": "FAIL", "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

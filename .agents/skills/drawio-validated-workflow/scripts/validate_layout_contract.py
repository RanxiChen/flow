#!/usr/bin/env python3
import json
import sys
from pathlib import Path


REQUIRED = {
    "standards_file": str,
    "applied_standard_rules": list,
    "diagram_type": str,
    "purpose": str,
    "audience": str,
    "style": (str, dict),
    "assumptions": list,
    "canvas": dict,
    "regions": list,
    "nodes": list,
    "edges": list,
    "typography": dict,
    "color_roles": dict,
    "domain_rules": dict,
    "must_do": list,
    "must_not": list,
    "review_checklist": list,
}


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: validate_layout_contract.py <layout.json>")
    path = Path(sys.argv[1])
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(str(exc))
    if not isinstance(data, dict):
        fail("top level must be an object")
    for key, expected in REQUIRED.items():
        if key not in data:
            fail(f"missing required key: {key}")
        if not isinstance(data[key], expected):
            fail(f"{key} has the wrong type")
    if not data["nodes"]:
        fail("nodes must not be empty")
    for collection in ("regions", "nodes", "edges"):
        ids = [item.get("id") for item in data[collection] if isinstance(item, dict)]
        if len(ids) != len(data[collection]) or any(not isinstance(i, str) or not i for i in ids):
            fail(f"every {collection} item must have a non-empty string id")
        if len(ids) != len(set(ids)):
            fail(f"duplicate id in {collection}")
    for collection in ("must_do", "must_not", "review_checklist"):
        values = data[collection]
        ids = [item.get("id") for item in values if isinstance(item, dict)]
        if len(ids) != len(values) or any(not isinstance(i, str) or not i for i in ids):
            fail(f"every {collection} item must have a non-empty string id")
        if len(ids) != len(set(ids)):
            fail(f"duplicate id in {collection}")
    diagram_type = data["diagram_type"].lower()
    if "electrical" in diagram_type or "circuit" in diagram_type:
        electrical = data["domain_rules"].get("electrical")
        if not isinstance(electrical, dict) or not isinstance(electrical.get("nets"), list):
            fail("electrical diagrams require domain_rules.electrical.nets")
        if not electrical["nets"]:
            fail("domain_rules.electrical.nets must not be empty")
        for index, net in enumerate(electrical["nets"]):
            if not isinstance(net, dict) or not isinstance(net.get("id"), str):
                fail(f"electrical net {index} needs an id")
            pins = net.get("pins")
            if not isinstance(pins, list) or len(pins) < 2:
                fail(f"electrical net {net.get('id', index)} needs at least two pins")
            if any(not isinstance(pin, str) or "." not in pin for pin in pins):
                fail(f"electrical net {net.get('id', index)} has an invalid component.port pin")
    print(
        "PASS: layout contract "
        f"type={data['diagram_type']} nodes={len(data['nodes'])} edges={len(data['edges'])}"
    )


if __name__ == "__main__":
    main()

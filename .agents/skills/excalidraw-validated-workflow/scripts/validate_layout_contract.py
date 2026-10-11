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
    print(
        "PASS: layout contract "
        f"type={data['diagram_type']} nodes={len(data['nodes'])} edges={len(data['edges'])}"
    )


if __name__ == "__main__":
    main()

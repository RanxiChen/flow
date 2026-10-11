#!/usr/bin/env python3
import json
import sys
from pathlib import Path


REQUIRED = {
    "diagram_type": str,
    "notation": str,
    "research_status": str,
    "sources": list,
    "semantic_rules": list,
    "visual_rules": list,
    "backend_constraints": list,
    "assumptions": list,
    "unverified_rules": list,
}
STATUSES = {"validated_profile", "researched", "convention_only"}


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def objects_with_unique_ids(name: str, values: object) -> list[dict]:
    if not isinstance(values, list):
        fail(f"{name} must be an array")
    result: list[dict] = []
    ids: list[str] = []
    for index, item in enumerate(values):
        if not isinstance(item, dict):
            fail(f"{name}[{index}] must be an object")
        item_id = item.get("id")
        if not isinstance(item_id, str) or not item_id:
            fail(f"{name}[{index}].id must be a non-empty string")
        ids.append(item_id)
        result.append(item)
    if len(ids) != len(set(ids)):
        fail(f"duplicate id in {name}")
    return result


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: validate_diagram_standards.py <standards.json>")
    path = Path(sys.argv[1])
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(str(exc))
    if not isinstance(data, dict):
        fail("top level must be an object")
    for key, expected in REQUIRED.items():
        if key not in data or not isinstance(data[key], expected):
            fail(f"missing or invalid key: {key}")
    if data["research_status"] not in STATUSES:
        fail("research_status must be validated_profile, researched, or convention_only")

    sources = objects_with_unique_ids("sources", data["sources"])
    source_ids = {item["id"] for item in sources}
    if not sources:
        fail("sources must not be empty")
    for index, source in enumerate(sources):
        for key in ("title", "authority", "applies_to"):
            if key not in source:
                fail(f"sources[{index}] missing {key}")
        if source["authority"] not in {"official", "primary", "local_validated", "design_convention"}:
            fail(f"sources[{index}].authority is invalid")
        if not isinstance(source["applies_to"], list):
            fail(f"sources[{index}].applies_to must be an array")

    rule_count = 0
    for collection in ("semantic_rules", "visual_rules", "backend_constraints"):
        rules = objects_with_unique_ids(collection, data[collection])
        rule_count += len(rules)
        for index, rule in enumerate(rules):
            if not isinstance(rule.get("text"), str) or not rule["text"]:
                fail(f"{collection}[{index}].text must be a non-empty string")
            cited = rule.get("source_ids", [])
            convention = rule.get("design_convention") is True
            if not isinstance(cited, list) or any(item not in source_ids for item in cited):
                fail(f"{collection}[{index}].source_ids contains an unknown source")
            if not cited and not convention:
                fail(f"{collection}[{index}] needs source_ids or design_convention=true")

    print(
        f"PASS: standards type={data['diagram_type']} "
        f"status={data['research_status']} sources={len(sources)} rules={rule_count}"
    )


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Strict structural validator for uncompressed native Draw.io XML."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def fail(message: str) -> None:
    raise ValueError(message)


def graph_models(document: ET.Element) -> list[tuple[str, ET.Element]]:
    root_name = local_name(document.tag)
    if root_name == "mxGraphModel":
        return [("Page-1", document)]
    if root_name != "mxfile":
        fail(f"unexpected XML root: {root_name}")

    pages: list[tuple[str, ET.Element]] = []
    diagrams = [child for child in document if local_name(child.tag) == "diagram"]
    if not diagrams:
        fail("mxfile contains no diagram pages")

    for index, diagram in enumerate(diagrams):
        name = diagram.get("name") or f"Page-{index + 1}"
        models = [child for child in diagram if local_name(child.tag) == "mxGraphModel"]
        if not models:
            fail(f"page {name!r} is compressed or lacks a direct mxGraphModel")
        if len(models) != 1:
            fail(f"page {name!r} contains {len(models)} mxGraphModel elements")
        pages.append((name, models[0]))
    return pages


def validate_page(name: str, model: ET.Element) -> dict[str, object]:
    roots = [child for child in model if local_name(child.tag) == "root"]
    if len(roots) != 1:
        fail(f"page {name!r} must contain exactly one root element")

    cells = [element for element in roots[0].iter() if local_name(element.tag) == "mxCell"]
    if not cells:
        fail(f"page {name!r} contains no mxCell elements")

    ids = [cell.get("id") for cell in cells]
    if any(cell_id is None or cell_id == "" for cell_id in ids):
        fail(f"page {name!r} contains an mxCell without an id")
    concrete_ids = [cell_id for cell_id in ids if cell_id is not None]
    if len(concrete_ids) != len(set(concrete_ids)):
        fail(f"page {name!r} contains duplicate mxCell ids")

    cell_by_id = {cell_id: cell for cell_id, cell in zip(concrete_ids, cells)}
    if "0" not in cell_by_id:
        fail(f"page {name!r} lacks root cell id=0")
    if "1" not in cell_by_id or cell_by_id["1"].get("parent") != "0":
        fail(f"page {name!r} lacks default layer id=1 parent=0")

    for cell_id, cell in cell_by_id.items():
        parent = cell.get("parent")
        if parent and parent not in cell_by_id:
            fail(f"page {name!r} cell {cell_id!r} has dangling parent {parent!r}")

    edges = [cell for cell in cells if cell.get("edge") == "1"]
    vertices = [cell for cell in cells if cell.get("vertex") == "1"]
    for edge in edges:
        edge_id = edge.get("id", "<unknown>")
        source = edge.get("source")
        target = edge.get("target")
        if not source or not target:
            fail(f"page {name!r} edge {edge_id!r} lacks source or target")
        if source not in cell_by_id or target not in cell_by_id:
            fail(f"page {name!r} edge {edge_id!r} has a dangling endpoint")
        geometries = [child for child in edge if local_name(child.tag) == "mxGeometry"]
        if len(geometries) != 1:
            fail(f"page {name!r} edge {edge_id!r} must contain exactly one mxGeometry")
        geometry = geometries[0]
        if geometry.get("relative") != "1" or geometry.get("as") != "geometry":
            fail(f"page {name!r} edge {edge_id!r} has invalid relative geometry")

    return {
        "name": name,
        "cells": len(cells),
        "vertices": len(vertices),
        "edges": len(edges),
        "unique_ids": len(cell_by_id),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    args = parser.parse_args()

    try:
        path = args.input.expanduser().resolve(strict=True)
        text = path.read_text(encoding="utf-8")
        if "<!--" in text:
            fail("XML comments are not allowed")
        document = ET.fromstring(text)
        pages = [validate_page(name, model) for name, model in graph_models(document)]
        print(
            json.dumps(
                {
                    "status": "PASS",
                    "input": str(path),
                    "bytes": path.stat().st_size,
                    "pages": pages,
                },
                ensure_ascii=False,
            )
        )
        return 0
    except (OSError, ET.ParseError, ValueError) as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())


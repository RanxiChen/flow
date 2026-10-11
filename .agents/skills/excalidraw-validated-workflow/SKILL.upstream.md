---
name: excalidraw-validated-workflow
description: Generate, revise, structurally validate, locally render, and visually inspect native editable Excalidraw diagrams. Use whenever the user asks OMP for an Excalidraw file, hand-drawn diagram, whiteboard sketch, concept map, teaching illustration, informal architecture sketch, or editable .excalidraw artifact that must actually open and be reviewed.
---

# Excalidraw Validated Workflow

Produce a local editable `.excalidraw` JSON file. Do not treat an MCP scene, checkpoint, screenshot, or sharing URL as the native artifact.

## Required loop

1. Restate the purpose, audience, required content, language, and outputs.
2. Choose a workspace-owned output directory. Preserve every source revision.
3. Read [references/standards-research.md](references/standards-research.md)
   and [references/diagram-layout-contract.md](references/diagram-layout-contract.md).
4. Invoke `diagram-designer`, fixed to `tongming/sophnet/gpt-5.5`. Save
   `standards_json` as `<slug>-standards.json` and `contract_json` as
   `<slug>-layout-v1.json`, then validate both. The custom agent is blocking:
   do not proceed until its settled structured result is returned. The
   controller may serialize those exact returned strings, but may not invent
   or repair their contents.

   ```bash
   python3 skill://excalidraw-validated-workflow/scripts/validate_diagram_standards.py \
     <slug>-standards.json
   python3 skill://excalidraw-validated-workflow/scripts/validate_layout_contract.py \
     <slug>-layout-v1.json
   ```

   A non-JSON or incomplete contract is a routing failure, not permission to
   skip the design layer.
5. If Excalidraw MCP is available, call `read_me` before using `create_view`.
6. Invoke the `diagram-generator` task agent to generate native JSON with
   `type`, `version`, `source`, `elements`, `appState`, and `files`, following
   [references/excalidraw-json.md](references/excalidraw-json.md), and save
   `<slug>-v1.excalidraw`. Give it the standards and contract files. This
   agent is fixed to `tongming/sophnet/gpt-5.5`; never generate or repair the
   source in the controller or a generic `task` agent. It is blocking: wait
   for its settled result before validating the artifact.
7. If either specialized agent is unavailable or resolves to a different
   model, stop with `DIAGRAM_ROUTING_UNAVAILABLE`. On transport failure or
   timeout, retry only the same specialized agent within the 10-attempt budget;
   never let the controller take over.
8. Validate it:

   ```bash
   python3 skill://excalidraw-validated-workflow/scripts/validate_excalidraw.py <input.excalidraw>
   ```

9. Validate contract conformance:

   ```bash
   python3 skill://excalidraw-validated-workflow/scripts/validate_contract_conformance.py \
     <slug>-standards.json <slug>-layout-v1.json <slug>-vN.excalidraw
   ```

10. Render a deterministic local SVG:

   ```bash
   bash skill://excalidraw-validated-workflow/scripts/render_excalidraw.sh \
     <input.excalidraw> <preview.svg>
   ```

11. Call `inspect_image` on the SVG when supported. If it rejects SVG, create a temporary high-resolution review raster:

   ```bash
   RASTER_SCALE=2 \
   bash skill://excalidraw-validated-workflow/scripts/rasterize_svg_for_review.sh \
     <preview.svg> /tmp/<slug>-review.png
   ```

   Inspect that temporary image, save the verdict, then remove it. Never place it beside deliverables.
12. Save `<slug>-vN-review.json` with iteration, standards and contract
    versions, design/generator/reviewer models, all gate results, verdict, and
    exact defects.
13. On FAIL, invoke `diagram-generator` with the current source, standards,
    contract, and review JSON to create the next source revision.
14. If the same defect category survives two consecutive repairs, or review
    identifies a planning defect, invoke `diagram-designer` to amend the
    standards or contract before the next generator call.
15. Re-run all gates and stop immediately on PASS.

Allow at most 10 generator dispatches, including transport failures and source
revisions. If attempt 10 still fails, mark `NON_CONVERGED`, do not create
`-final` artifacts, preserve all evidence, and report the remaining defects or
routing failures. Never ask the user about an ambiguity created by an agent;
record a reasonable assumption unless the original request materially lacks a
required choice.

## Structural rules

- Use unique string IDs and finite geometry.
- Store line and arrow points relative to the element origin.
- Make every binding ID resolve to an existing element.
- Do not leave only one side of an arrow bound; use both bindings or neither.
- Keep `boundElements`, `containerId`, and frame references consistent.
- Preserve `files` entries when images are present.
- Do not represent arrows with text glyphs or emoji.

Use `--strict-bindings` when every arrow is intended to connect two nodes.

## Preview and MCP boundary

The bundled renderer is a deterministic geometry and legibility SVG. It does not reproduce Excalidraw rough strokes perfectly. Judge exact hand-drawn texture in the official editor, but never skip visual review.

Use MCP `create_view` as an optional interactive second view. Call MCP `read_me` first. Never call `export_to_excalidraw` unless the user explicitly authorizes uploading or sharing.

## Visual review

Ask `inspect_image` for a structured PASS/FAIL verdict covering missing content, overlaps, clipping, tiny text, poor contrast, excessive empty space, unclear arrows, crossed connectors, detached endpoints, inconsistent layout, and exact required changes. Do not accept generic praise.

## Output preservation

Preserve `<slug>-v1.excalidraw/.svg/-review.json` through
`<slug>-v10.excalidraw/.svg/-review.json`. Create `<slug>-final.excalidraw`
and `<slug>-final.svg` only after PASS. Never create final copies for
`NON_CONVERGED`, and never delete editable sources.

Embed the SVG in Markdown with `![Diagram](./<slug>-final.svg)`.

## Final report

Report the design, generation, and visual models; standards and layout-contract
paths and versions; editable source and preview paths; element and arrow
counts; structural and conformance results; visual verdict; corrections;
iteration count; unresolved defects; and `PASS` or `NON_CONVERGED` status.

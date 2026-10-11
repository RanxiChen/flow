---
name: drawio-validated-workflow
description: Generate, revise, structurally validate, export to SVG, and visually inspect native editable Draw.io files. Use whenever the user asks OMP to create or fix a .drawio diagram, flowchart, architecture diagram, sequence diagram, class diagram, ER diagram, network diagram, electrical circuit or schematic, or a diagram that must render in VS Code or Markdown.
---

# Draw.io Validated Workflow

Produce a real editable `.drawio` file. Do not treat an MCP success message, URL, or XML shown only in chat as an artifact.

## Required loop

1. Restate the diagram's purpose, content, audience, and required output.
2. Choose a workspace-owned output directory. Preserve the raw version and write revisions as new files.
3. Read [references/standards-research.md](references/standards-research.md)
   and [references/diagram-layout-contract.md](references/diagram-layout-contract.md).
4. Invoke `diagram-designer`. It is fixed to
   `tongming/sophnet/gpt-5.5`. Save `standards_json` as
   `<slug>-standards.json` and `contract_json` as `<slug>-layout-v1.json`.
   The custom agent is blocking: do not proceed until its settled structured
   result is returned. The controller may serialize those exact returned
   strings, but may not invent or repair their contents.
   Validate both:

   ```bash
   python3 skill://drawio-validated-workflow/scripts/validate_diagram_standards.py \
     <slug>-standards.json
   python3 skill://drawio-validated-workflow/scripts/validate_layout_contract.py \
     <slug>-layout-v1.json
   ```

   A missing, non-JSON, or incomplete result is a failed planning attempt, not
   permission to skip standards research or design.
5. Invoke the `diagram-generator` task agent to generate uncompressed native
   XML with `mxfile > diagram > mxGraphModel > root` and write
   `<slug>-v1.drawio`. Give it the standards and contract files. This agent is
   fixed to `tongming/sophnet/gpt-5.5`; never generate or repair source in the
   controller or a generic `task` agent. It is blocking: wait for its settled
   result before validating the artifact.
6. If either specialized agent is unavailable or resolves to a different
   model, stop with `DIAGRAM_ROUTING_UNAVAILABLE`. Do not silently inherit the
   current OMP model. On transport failure or timeout, retry only the same
   specialized agent within the 10-attempt budget; never let the controller
   take over.
7. Run:

   ```bash
   python3 skill://drawio-validated-workflow/scripts/validate_drawio.py <input.drawio>
   ```

   If OMP cannot execute a `skill://` path directly, first read the skill resource to resolve its filesystem path and invoke that path.
   For an electrical circuit or schematic, also follow the electrical-symbol gate below.
8. Check that the native artifact implements the standards and contract:

   ```bash
   python3 skill://drawio-validated-workflow/scripts/validate_contract_conformance.py \
     <slug>-standards.json <slug>-layout-v1.json <slug>-vN.drawio
   ```

   Any missing contract node or edge, mismatched endpoint, unimplemented rule
   reference, or electrical net/pin failure is FAIL.
9. Export the validated file to SVG:

   ```bash
   bash skill://drawio-validated-workflow/scripts/render_drawio.sh <input.drawio> <preview.svg>
   ```

10. Confirm that rendering exited successfully and produced a non-empty SVG.
11. Call `inspect_image` on the SVG when supported. If it rejects SVG, create a temporary high-resolution review raster:

   ```bash
   RASTER_SCALE=2 \
   bash skill://drawio-validated-workflow/scripts/rasterize_svg_for_review.sh \
     <preview.svg> /tmp/<slug>-review.png
   ```

   Inspect that temporary image, save the verdict, then remove it. Never place it beside deliverables.
12. Save the critique as `<slug>-vN-review.json`, including iteration,
    standards and contract versions, design/generator/reviewer models, every
    gate result, verdict, and exact defects.
13. On FAIL, invoke `diagram-generator` with the current source, standards,
    contract, and review JSON to create `<slug>-vN+1.drawio`. Preserve all
    previous artifacts.
14. If the same defect category survives two consecutive repairs, or review
    identifies a planning defect, invoke `diagram-designer` to amend the
    standards or contract before the next generator call.
15. Re-run every gate after every revision. Accept only when standards,
    structure, contract conformance, domain audit, render, and visual review
    all pass.

Allow at most 10 generator dispatches, including transport failures and source
revisions. Stop immediately on PASS. After attempt 10, mark the run
`NON_CONVERGED`, do not create `-final` artifacts, preserve all evidence, and
tell the user the remaining defects or routing failures. During the loop,
never ask the user about an ambiguity created by an agent; record a reasonable
assumption in the standards/contract. Ask only when the original request lacks
a choice that materially changes meaning.

## MCP boundary

- Use Draw.io MCP `search_shapes` when exact vendor, cloud, electrical, BPMN, or other specialized stencil styles are needed.
- Treat shape-search results as candidates, not proof that the visible symbol has the intended semantics.
- Use `list_pages`, `get_page`, and `set_page` for existing local multi-page files.
- Do not use `open_drawio_xml`, `open_drawio_mermaid`, or `open_drawio_csv` as proof that a local `.drawio` artifact was created.
- Never accept “opened successfully” without a saved file, successful CLI render, and visual review.

## Electrical symbol gate

For every circuit or electrical schematic:

1. Read [references/electrical-symbol-validation.md](references/electrical-symbol-validation.md).
2. Search for each specialized symbol and retain the exact returned style and native width/height.
3. When a symbol has not already been validated for the current Draw.io version, create a temporary candidate sheet with visible labels, export it to SVG, and inspect the actual rendering.
4. Verify the visible convention, not only the search title:
   - resistor body is IEC rectangular or ANSI zigzag as requested;
   - current source contains a visible arrow with the correct direction;
   - voltage source contains visible polarity marks in the correct orientation;
   - terminals and wire endpoints visibly meet;
   - the symbol preserves its native aspect ratio.
5. Reject a candidate that is semantically ambiguous or visually wrong. Use another validated stencil or compose the conventional symbol from basic shapes.
6. Audit the completed circuit:

   ```bash
   python3 skill://drawio-validated-workflow/scripts/audit_electrical_shapes.py \
     <input.drawio> --resistor-standard ansi --strict-current-source \
     --strict-voltage-source
   ```

   Choose `ansi`, `iec`, or `any` from the user's requested convention. Use
   `--strict-current-source` when an independent current source is present; the
   known ambiguous stencil must then fail.
7. Do not accept the final circuit until structural validation, electrical audit, SVG export, and visual review all pass.
8. Run contract conformance and require every expected electrical pin to be
   present on its declared net. An electrical artifact with zero native edges,
   conductor vertices, or a wire that only touches a stencil bounding box is
   FAIL. A resistor whose top/bottom versus left/right ports disagree with its
   rendered rotation is also FAIL.

## Structural requirements

- Use well-formed XML without XML comments.
- Use unique `mxCell` IDs within each page.
- Include root cells `id="0"` and `id="1" parent="0"`.
- Ensure every `parent`, `source`, and `target` reference resolves.
- Give every edge valid source and target cells and a relative `mxGeometry`.
- Escape XML special characters in attribute values.
- Keep labels readable and geometry coordinates explicit.
- Read [references/xml-reference.md](references/xml-reference.md) when generating non-trivial XML.

## Visual review prompt

Ask `inspect_image` to return a concise structured verdict covering:

- missing nodes, labels, arrows, or required relationships;
- overlaps between boxes, labels, connectors, or containers;
- clipped or unreadable text;
- disconnected connectors or visible gaps at intended terminals;
- false crossings, apparent shorts, ambiguous arrow direction, or excessive detours;
- inconsistent alignment, spacing, sizing, colors, and hierarchy;
- whether the result is presentation-ready;
- exact defects to change, using visible labels and locations.

For electrical diagrams also ask it to identify:

- wrong IEC/ANSI symbol convention;
- missing current arrows or voltage polarity marks;
- arrow or polarity direction inconsistent with labels;
- wires that stop short of terminals, cross without intended junctions, or appear shorted;
- distorted symbols caused by incorrect width/height.

Ask about visible facts. Do not accept a generic statement such as “looks good.”
For electrical diagrams, inspect the complete schematic and zoomed terminal
regions. A later PASS cannot erase a previously observed terminal gap unless
the revised pixels visibly show continuous contact.

## Dual acceptance

- Structural PASS plus visual FAIL is FAIL.
- Visual PASS plus structural FAIL is FAIL.
- CLI export failure or missing SVG is FAIL.
- Electrical audit FAIL is FAIL for a circuit or schematic.
- Contract conformance FAIL is FAIL.
- Only standards PASS plus structural PASS plus conformance PASS plus domain
  audit PASS plus render PASS plus visual PASS is accepted.

## Output preservation

Use a stable naming pattern:

```text
<slug>-v1.drawio
<slug>-v1.svg
<slug>-v1-review.json
<slug>-standards.json
<slug>-layout-v1.json
<slug>-v2.drawio
<slug>-v2.svg
<slug>-v2-review.json
...
<slug>-v10.drawio
<slug>-v10.svg
<slug>-v10-review.json
<slug>-final.drawio
<slug>-final.svg
```

Never delete the native `.drawio` source after export.
Create `-final` copies only after a PASS. Never create them for
`NON_CONVERGED`.

Embed the SVG in Markdown with `![Diagram](./<slug>-final.svg)`.

## Final report

Report:

- generation model and provider;
- design model and provider;
- standards file, research status, and applied rule IDs;
- layout-contract path and version;
- visual-review model and provider when available;
- native editable artifact path;
- rendered SVG path;
- validator result, CLI exit code, and output size;
- electrical audit result and chosen symbol convention when applicable;
- visual verdict and corrections made;
- unresolved defects;
- iteration count and `PASS` or `NON_CONVERGED` status;
- whether the final artifact satisfies every acceptance gate.

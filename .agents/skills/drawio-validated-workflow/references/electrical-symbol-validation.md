# Electrical symbol validation

## Mandatory rule

Never accept a circuit symbol from `search_shapes` by title or shape ID alone.
Render unfamiliar candidates with the installed Draw.io Desktop version and
inspect their visible semantics before using them in a circuit.

## Connectivity representation

- Implement every conductor as a native Draw.io edge with explicit `source`
  and `target`; never use a thin filled rectangle as a wire.
- Preserve each contract edge ID in the artifact.
- Add `net`, `sourcePort`, and `targetPort` attributes to electrical edges.
- Attach component endpoints directly to component cells using visible
  top/bottom or left/right ports.
- Match port names to visible symbol orientation. The native resistor stencils
  are horizontal: `left`/`right` ports require no 90-degree rotation;
  `top`/`bottom` ports require `rotation=90` or `rotation=270`.
- Use pre-rotation connection coordinates for rotated resistor stencils:
  - `rotation=90`: visual `top` is `(X=0,Y=0.5)` and visual `bottom` is
    `(X=1,Y=0.5)`;
  - `rotation=270`: visual `top` is `(X=1,Y=0.5)` and visual `bottom` is
    `(X=0,Y=0.5)`;
  - unrotated visual `left`/`right` are `(0,0.5)` / `(1,0.5)`.
  Apply these as `exitX/exitY` when the resistor is the edge source and
  `entryX/entryY` when it is the target.
- Reject an electrical diagram with zero native edges.
- Reject contact with only a stencil bounding box. Render the candidate and
  verify that the connector reaches the visible terminal.
- When a stencil has unusable terminal geometry, compose the conventional
  symbol from basic primitives whose visible perimeter is the connection
  perimeter.

## Verified snapshot

Verified with Draw.io Desktop 30.0.4 on 2026-07-28:

| Shape | Actual rendering | Decision |
|---|---|---|
| `mxgraph.electrical.resistors.resistor_1` | IEC rectangular resistor | Use only for IEC rectangular convention |
| `mxgraph.electrical.resistors.resistor_2` | ANSI zigzag resistor | Use for zigzag/ANSI requests |
| `mxgraph.electrical.signal_sources.current_source` | Two overlapping circles; no conventional internal current arrow was visible | Do not use as a standard independent current source without renewed visual proof |
| `mxgraph.electrical.signal_sources.dc_source_1` | Circle with visible `+` and `-` marks, but the circle is inset from the cell bounding box; direct top/bottom edges visibly stop short in Draw.io 30.0.4 | Do not directly connect this stencil in a completed circuit; compose a perimeter-connected voltage source |

Revalidate this table after upgrading Draw.io Desktop or its electrical stencil
library.

The audit rejects a resistor whose declared port direction disagrees with its
rotation because that mismatch makes a native edge terminate at empty bounding
box space rather than at the visible lead. It also rejects a rotated resistor
whose edge anchor does not match the mapping above.

## Candidate-sheet procedure

1. Place each candidate on a temporary page with its exact shape ID as a label.
2. Preserve the search result's native aspect ratio. Scaling by a common factor
   is allowed; independent width/height stretching is not.
3. Export the candidate page with `render_drawio.sh`.
4. Rasterize only to `/tmp` if the visual model cannot inspect SVG.
5. Check visible body style, arrow, polarity, terminals, and orientation.
6. Record the accepted ID and convention in the review artifact.
7. Delete the temporary raster after review.

## Independent current source fallback

If the stencil lacks a visible internal arrow, compose the standard symbol:

- one circular outline;
- one centered arrow inside the circle;
- two collinear external terminals;
- an `I` label and optional value;
- arrow direction matching the stated positive reference current.

Group the parts for editing, but keep the electrical wires as real edges whose
endpoints visibly touch the two external terminals.

## DC voltage source fallback

For Draw.io 30.0.4, do not directly attach circuit edges to
`mxgraph.electrical.signal_sources.dc_source_1`. Compose the source:

- use the contract component ID on a basic ellipse vertex;
- add `electricalType="voltage_source"` and a `polarity` attribute such as
  `positive-top` or `positive-bottom`;
- add separate visible `+` and `-` text vertices with
  `component="<source-id>"` and `polarityMark="+"` / `polarityMark="-"`;
- connect the contract edges directly to the ellipse component with
  `exitPerimeter=1` or `entryPerimeter=1`;
- render and inspect both terminal regions at high resolution.

The electrical audit recognizes this composed representation and rejects
perimeter-disabled incident edges.

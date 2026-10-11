# Diagram layout contract

Before source generation, read
[standards-research.md](standards-research.md), invoke the `diagram-designer`
task agent, and save its two JSON results as `<slug>-standards.json` and
`<slug>-layout-v1.json`.

The contract must reference the standards filename and applied rule IDs, then
define:

- `diagram_type`, `purpose`, `audience`, `style`, and `assumptions`;
- `canvas`: orientation, aspect ratio, margins, grid, reading direction;
- `regions`: stable IDs, hierarchy, relative placement, alignment, spacing;
- `nodes`: stable IDs, roles, semantic shapes, sizes, region and rank;
- `edges`: stable IDs, endpoints, ports, routing, labels, crossings and arrows;
- `typography`, `color_roles`, `must_do`, `must_not`;
- a diagram-specific `review_checklist`.

The contract is an acceptance input, not advisory prose. The generator must
implement it. If visual review proves the contract itself defective, invoke
`diagram-designer` again and save the amended contract with the next version.
Do not silently rewrite the contract in the controller or generator.

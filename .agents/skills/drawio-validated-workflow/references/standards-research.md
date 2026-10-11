# Standards research

Classify the diagram before designing it. Reuse a validated local profile when
it fully covers a stable convention. Otherwise research official or primary
sources with `web_search`, then record the applicable rules and source
authority in `<slug>-standards.json`.

Separate:

- semantic rules: what the diagram means and which relationships are valid;
- visual rules: hierarchy, reading order, alignment, spacing, and notation;
- backend constraints: how Draw.io or Excalidraw can implement the rules.

User-specified notation overrides a default convention. Never describe an
informal design convention as an official standard. Put unresolved or
unsupported rules in `unverified_rules`; they cannot be acceptance evidence.

Use these profile families when applicable:

- electrical schematic: symbol convention, component pins, nets, junctions;
- flowchart or BPMN: node semantics, branch labels, path completeness;
- architecture or network: boundaries, containment, interfaces, links;
- sequence: lifelines, chronological messages, synchronization;
- UML or ER: relationship types, direction, multiplicity, identifiers;
- mind map or teaching diagram: hierarchy, balance, grouping, legibility.

Use Draw.io `search_shapes` only after semantic notation is chosen. Shape search
is backend discovery, not standards evidence. Render and inspect unfamiliar
stencils before accepting their visible semantics or connection geometry.

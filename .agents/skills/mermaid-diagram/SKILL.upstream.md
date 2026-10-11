---
name: mermaid-diagram
description: Create clear, validated Mermaid diagrams from supplied facts, models, workflows, architectures, and concepts. Use when another skill or the user needs information visualized as Mermaid.
---

# Mermaid Diagram Creator

Generate `.mmd` files (or fenced ` ```mermaid ` blocks in `.md` files when embedding in docs).

## Reference Files (load as needed)

| File | Load when... |
|------|-------------|
| `references/types/<type>.md` | After choosing diagram type (Step 6). Full syntax, examples, layout tips, line-break rules, and pitfalls for that type. |
| `references/syntax-pitfalls.md` | Render fails or you need to debug syntax. Covers quoting rules, `<br>` compatibility matrix (Section 9), reserved words, and common errors. |
| `references/mermaid-theme.md` | **The single theming file.** Dark/light mode color guidelines, `classDef` semantic style recipes (trigger, success, error, ai, decision), and per-diagram-type support notes. |
| `references/render_mermaid.sh` | The render script. Invoked via bash, not read. See Render & Validate below. |
| `references/examples/` | Validated `.mmd` examples (`architecture.mmd`, `sequence-api.mmd`). Review for inspiration or to verify your syntax against known-good patterns. |

## Customization

To customize brand styles, edit `references/mermaid-theme.md` — this is the single source of truth for all theming (semantic node styles and dark/light mode color guidelines).

---

## Source of Truth

This skill is a visualization specialist, not an analysis or discovery skill.

When another skill or agent supplies components, relationships, flows,
states, dependencies, evidence, or other verified findings:

- Treat those findings as authoritative.
- Do not independently re-analyze the codebase or source material.
- Do not invent components, relationships, dependencies, states,
  protocols, calls, or runtime behavior.
- Preserve uncertainty and qualifications from the source analysis.
- You may reorganize information visually, but must not change its
  semantic meaning.
- If information required for the diagram is missing, omit it or
  identify the gap rather than inferring unsupported facts.

When the user directly asks for a diagram without supplying an analyzed
model, gather only the information necessary to construct the requested
diagram.

---

## Core Philosophy

**Diagrams should ARGUE, not DISPLAY.**

A diagram isn't formatted text. It's a visual argument that shows relationships, causality, and flow that words alone can't express. The shape should BE the meaning.

**The Isomorphism Test**: If you removed all text, would the structure alone communicate the concept? If not, redesign.

**The Education Test**: Could someone learn something concrete from this diagram, or does it just label boxes? A good diagram teaches—it uses verified formats, event names, and concrete examples when available.
| Bad (Displaying) | Good (Arguing) |
|------------------|----------------|
| 5 equal boxes with labels | Each concept has a shape that mirrors its behavior |
| Card grid layout | Visual structure matches conceptual structure |
| Icons decorating text | Shapes that ARE the meaning |
| Same container for everything | Distinct visual vocabulary per concept |

---

## Design Process

### Step 0: Assess Depth and Audience

Before anything else, determine what level of detail this diagram needs:

- **Simple/Conceptual**: Abstract shapes, labels, relationships. Use when explaining a mental model, the audience already knows the details, or the concept IS the abstraction.
- **Comprehensive/Technical:** Concrete examples, code snippets, and real
  data when supplied or verified by the source analysis. Never invent
  concrete details solely to make a diagram more comprehensive.

| Simple Diagram | Comprehensive Diagram |
|----------------|----------------------|
| Generic labels: "Input" → "Process" → "Output" | Shows what the input/output actually looks like |
| Named boxes: "API", "Database", "Client" | Named boxes + verified requests/responses when available |
| "Events" or "Messages" label | Verified event/message names when available |
| ~30 seconds to explain | ~2-3 minutes of teaching content |
| Viewer learns the structure | Viewer learns the structure AND the details |

Match density to audience:

| Audience | Node Count | Detail Level |
|----------|-----------|--------------|
| Executive / overview | 5–7 | High-level boxes, no attributes |
| Technical documentation | 10–15 | Subgraphs, edge labels, evidence artifacts |
| Deep reference | 15–20, then split | Full attributes, Notes, companion files |

When the same system needs multiple audiences, create separate diagrams — not one diagram that tries to serve everyone.

### Step 1: Establish the Input Model

Determine where the diagram's facts come from.

If invoked by another analysis skill:
1. Use the supplied model, evidence, and uncertainty as the source of truth.
2. Identify the components, relationships, flows, states, and boundaries
   that need visualization.
3. Preserve names and terminology from the source analysis.
4. Do not perform duplicate discovery or repository analysis.

If invoked directly by the user:
1. Use information supplied by the user.
2. Inspect source material only when necessary to construct the diagram.
3. Distinguish known information from assumptions.
4. Never invent technical relationships to make a diagram look complete.

### Step 2: Understand the Visual Semantics

For each supplied concept, ask:

- What role does this concept play in the supplied model?
- What verified relationships exist between concepts?
- What is the important transformation, interaction, or flow?
- What would someone need to SEE to understand this?
- Which details are evidence and which are merely presentation?

Do not introduce new semantic relationships during this step.

### Step 3: Map Concepts to Patterns
For each concept, find the visual pattern that mirrors its behavior:

| If the concept... | Use this pattern |
|-------------------|------------------|
| Spawns multiple outputs | **Fan-out** (single node, multiple outgoing edges) |
| Combines inputs into one | **Convergence** (multiple nodes into one target) |
| Has hierarchy/nesting | **Tree** (mindmap or subgraph nesting) |
| Is a sequence of steps | **Timeline** (timeline diagram or sequence diagram) |
| Loops or improves continuously | **Spiral/Cycle** (state self-transitions or back-edges) |
| Transforms input to output | **Assembly line** (flowchart pipeline) |
| Compares two things | **Side-by-side** (parallel subgraphs) |
| Separates into phases | **Gap/Break** (subgraph boundaries) |

### Step 4: Ensure Variety
For multi-concept diagrams: **each major concept must use a different visual pattern**. No uniform cards or grids.

### Step 5: Sketch the Flow
Before writing syntax, mentally trace how the eye moves through the diagram. There should be a clear visual story.

### Step 6: Generate Mermaid Syntax
1. Choose diagram type using the Decision Matrix below, then load `references/types/<chosen-type>.md` for full syntax.
2. Write the syntax — start with a `%%` comment block naming the diagram type and describing each section.
3. Use meaningful node IDs (not `A`, `B`, `C` — use `authService`, `dbWrite`, `userInput`).
4. Add `classDef` for semantic styling (trigger, success, error, ai, decision) — pull from `references/mermaid-theme.md` (Section 2). Follow the color selection guidelines in Section 1 to ensure dark/light mode compatibility.
5. **Output format**: Create a `.mmd` file by default. Use a fenced ` ```mermaid ` block when the user says "embed", "add to docs", "in README", or the target is a `.md` file.

#### Evidence Artifacts (Comprehensive diagrams)

When evidence artifacts are supplied by the calling skill or source
material, use them where they improve understanding:

- Notes in sequence diagrams for verified payloads and message formats.
- Multi-line node labels for concise evidence.
- Subgraph titles for verified architectural boundaries.
- Companion `.md` files when supplied evidence is too large for the diagram.

Never fabricate evidence artifacts. If concrete examples were not supplied,
prefer an accurate abstract diagram over invented specificity.

#### Multi-Zoom (Comprehensive diagrams)

Aim for three zoom levels simultaneously:
1. **Summary Flow** — simplified overview of the full pipeline (`Input → Processing → Output`)
2. **Section Boundaries** — labeled subgraphs grouping related components (by responsibility, phase, or team)
3. **Detail Inside Sections** — evidence artifacts and concrete examples within each section

#### Splitting Large Diagrams

- Keep nodes to ~20 max before splitting into multiple diagrams.
- For complex systems: one `.mmd` per layer (Context → Container → Component).
- Break dense subgraphs into separate files with cross-references in prose.

### Step 7: Render & Validate
Run the render script and validate the output. See the **Render & Validate** section below.

---

## Diagram Type Decision Matrix

Quick lookup: match the visual pattern you need to the best diagram type, then open its reference file.

| Visual Pattern | Best Diagram Type | Reference |
|----------------|-------------------|-----------|
| Fan-out (one-to-many) | Flowchart | `references/types/flowchart.md` |
| Convergence (many-to-one) | Flowchart | `references/types/flowchart.md` |
| Sequence of steps / interactions | Sequence Diagram | `references/types/sequence.md` |
| Hierarchy / tree | Mindmap | `references/types/mindmap.md` |
| State machine / lifecycle | State Diagram | `references/types/state.md` |
| Object structure / relationships | Class Diagram | `references/types/class.md` |
| System architecture / context | C4 Diagram | `references/types/c4.md` |
| Timeline / ordered events | Timeline Diagram | `references/types/timeline.md` |
| Spiral/Cycle (loop) | State Diagram or Flowchart | `references/types/state.md` `references/types/flowchart.md` |
| Side-by-side comparison | Flowchart (parallel subgraphs) | `references/types/flowchart.md` |
| Data schema / entity relationships | ER Diagram | `references/types/er-diagram.md` |
| Flow volumes / resource allocation | Sankey Diagram | `references/types/sankey.md` |

### Excluded Diagram Types

Do **not** use these types — they lack structural argument capability or are too narrowly specialized:

| Type | Reason Excluded |
|------|-----------------|
| Pie Chart | Simple percentages; lacks structural argument capability |
| Gantt | Specifically for project scheduling, not conceptual mapping |
| Git Graph | Limited to git branch visualization |
| XY Chart | Quantitative data charting rather than structural diagramming |
| User Journey | Overly specific; typically better represented as a Sequence Diagram |

---

## Render & Validate (MANDATORY)

How to render: Run `references/render_mermaid.sh` from this skill's installed directory:

```bash
bash <skill-dir>/references/render_mermaid.sh <path.mmd> [output.png]
```

Resolve `<skill-dir>` from the location of this `SKILL.md`; do not assume a Claude-specific installation path.

First run downloads mmdc via npx — may take ~30s.

**The loop:**
1. Write Mermaid syntax.
2. Run render script.
3a. If mmdc fails with a syntax error: read the error message, fix the syntax. Consult `references/syntax-pitfalls.md` for the most common failures (usually: unquoted special chars, wrong keyword, missing `end`).
3b. If mmdc succeeds: view the PNG, assess the layout.
4. If layout is poor: restructure — reorder node/edge declarations to change placement, switch `rankDir` (TD ↔ LR), add or remove subgraphs.
5. Re-render → repeat until the layout communicates the concept cleanly.

**Label quoting rule**: Always use `["label text"]` instead of `[label text]` whenever the label contains parentheses, commas, `@`, `/`, `<`, `>`, or `:`. When in doubt, quote it — `["My label"]` is always safer than `[My label]`. This single rule prevents ~50% of render failures. See `references/syntax-pitfalls.md` for the full list.

**`flowchart` not `graph`**: Always use `flowchart TD` (modern), never `graph TD` (legacy). `graph` lacks subgraph direction support and other modern features.

**`end` is reserved**: A node whose entire label is `end` will be parsed as a block terminator. Always quote it: `B["end"]`.

**You cannot pixel-position elements.** All layout improvements come from declaration order, `rankDir`, subgraphs, and hidden lines — not coordinate adjustments.

**Line crossing reduction** — the primary visual quality challenge in auto-generated diagrams:
- **Declaration order matters**: The layout engine assigns ranks based on source order. Declare nodes in reading order (top→bottom or left→right). Earlier declarations get higher or leftmost ranks.
- **Minimize back-edges**: Every edge flowing "backwards" to an earlier node forces a crossing. For cycles, use State diagrams or isolate the back-edge in a subgraph.
- **Group related nodes in source**: Keep nodes in the same logical group adjacent in code. Scattered declarations → scattered placement → crossings.
- **Subgraphs as layout hints**: Wrapping related nodes in a `subgraph` constrains placement, preventing global crossings. Each subgraph can define its own `direction` (e.g., `direction LR`) to resolve density issues.
- **Reduce fan-out**: A single node with 5+ outgoing edges creates spider-web layouts. Insert a dispatcher/group node to flatten.
- **Switch orientation**: When crossings persist, try the orthogonal `rankDir` (`TD` ↔ `LR`). This alone often eliminates most crossings.
- **Hidden lines**: Consider whether invisible edges would help constrain node placement.

**When to stop**: syntax valid, layout communicates the concept, line crossings minimized, no overlapping labels, eye flows correctly through the diagram.

---

## Quality Checklist

Before finalizing any diagram:

### Source Integrity

Check first for technical diagrams:

1. Source of truth identified?
2. Every material node comes from the supplied model or source material?
3. Every material edge/relationship is supported by the supplied model?
4. Uncertainty from the source analysis preserved?
5. No invented APIs, calls, dependencies, states, payloads, or behavior?
6. Concrete evidence used only when actually supplied or verified?

### Diagram Design

1. **Isomorphism Test:** Does visual structure mirror the concept's behavior?
2. **Argument:** Does the diagram SHOW something text alone couldn't?
3. **Variety:** Does each major concept use a different visual pattern or node shape?
4. Diagram type chosen intentionally (matches the concept's pattern from decision matrix)?
5. Node IDs are readable, not single letters?

### Mermaid-Specific

1. `classDef` used for semantic styling (trigger, success, error, ai, decision)?
2. Every `classDef` sets `fill`, `stroke`, AND `color`?
3. Output format correct (`.mmd` vs fenced block)?
4. No excluded diagram types used?
5. Node/edge declaration order follows reading direction?
6. No single node has 5+ edges without a dispatcher split?
7. Back-edges isolated where appropriate?

### Render Validation (mandatory)

1. Syntax validated: `mmdc` runs without error?
2. PNG viewed and layout visually inspected?
3. No overlapping labels or crossed text?
4. Eye flows naturally through the diagram?
5. Line crossings minimized?

### Cleanup

Once the user is happy with the output, remove temporary output such as rendered PNGs or intermediate `.mmd` files that are no longer needed.

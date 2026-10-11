---
name: excalidraw-validated-workflow
description: Create editable Excalidraw whiteboard diagrams, check bindings/content
  contracts and render locally for visual review.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Establish the requested model and output purpose; read references/standards-research.md and references/diagram-layout-contract.md when building a formal content/layout contract. Produce standards.json and layout.json only to the extent useful for the requested figure. Confirm source authority and represent undecided mechanisms explicitly.

Create the native editable artifact, validate structure and any declared contract, render a preview, and inspect it visually. Use the local scripts with real filesystem paths relative to this skill; skill:// URIs from archived OMP instructions are not filesystem paths. The active agent can author and repair diagrams directly. There is no fixed model or OMP agent requirement; delegate only when the user or active instructions authorize it.

Use a bounded repair loop driven by concrete validation/visual defects, preserving native source and meaningful revisions. Report which checks passed and which could not run; never claim visual acceptance without looking at the rendering. Hardware semantics still require the design review and scenario checks.

Read references/excalidraw-json.md before authoring. Run scripts/validate_excalidraw.py and optional standards/layout/conformance checks, then scripts/render_excalidraw.sh for the local geometry preview. The renderer is not an exact reproduction of hand-drawn texture; inspect the official editor only when that matters. Save .excalidraw and preview files. Existing Excalidraw MCP tools are optional; do not upload or share a scene unless requested.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

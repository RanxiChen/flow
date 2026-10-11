---
name: drawio-validated-workflow
description: Create and review native Draw.io files against explicit content/connection
  contracts, structural checks and rendered previews.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Establish the requested model and output purpose; read references/standards-research.md and references/diagram-layout-contract.md when building a formal content/layout contract. Produce standards.json and layout.json only to the extent useful for the requested figure. Confirm source authority and represent undecided mechanisms explicitly.

Create the native editable artifact, validate structure and any declared contract, render a preview, and inspect it visually. Use the local scripts with real filesystem paths relative to this skill; skill:// URIs from archived OMP instructions are not filesystem paths. The active agent can author and repair diagrams directly. There is no fixed model or OMP agent requirement; delegate only when the user or active instructions authorize it.

Use a bounded repair loop driven by concrete validation/visual defects, preserving native source and meaningful revisions. Report which checks passed and which could not run; never claim visual acceptance without looking at the rendering. Hardware semantics still require the design review and scenario checks.

For a .drawio file, run scripts/validate_drawio.py. If standards/layout contracts are present, use scripts/validate_diagram_standards.py, scripts/validate_layout_contract.py and scripts/validate_contract_conformance.py as documented. Export with scripts/render_drawio.sh only when an available desktop CLI is detected. Electrical-symbol audits apply to electrical schematics, not ordinary cache block diagrams. Preserve .drawio plus preview and state the output paths.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

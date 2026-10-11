---
name: drawio
description: Create native editable Draw.io architecture, pipeline or interface diagrams
  and optional SVG/PNG/PDF exports.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Use [XML reference](references/xml-reference.md) for native files and [Mermaid reference](references/mermaid-reference.md) for supported standard diagrams. Save native sources alongside exports; retain .mmd inputs when used. Use existing Draw.io MCP tools when useful, or generate files directly; native XML needs no plugin or desktop app. Detect an available CLI before optional export/layout. Do not install applications or auto-open GUI solely because upstream defaults say so.

Identify model authority, nodes, edges, stage boundaries and pending decisions before drawing. Match user terminology and preserve uncertainty. For Breeze diagrams, label data/control direction, ownership and register boundaries; use [drawio-validated-workflow](../drawio-validated-workflow/SKILL.md) for structural/contract/visual validation. Keep editable files even after exports. A syntactically valid diagram does not prove the hardware behavior.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

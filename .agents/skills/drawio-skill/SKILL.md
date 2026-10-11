---
name: drawio-skill
description: Maintain editable Draw.io models with structural validation, source provenance,
  incremental layout-preserving updates and optional semantic queries.
license: MIT
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Use scripts/diagramctl.py and relevant [toolbox](references/toolbox.md), [IR](references/diagram-ir.md), [authoring](references/xml-authoring.md) and [troubleshooting](references/troubleshooting.md) resources. scripts/diagramctl.py doctor is a read-only dependency check; do not use GUI probes unless useful to the task.

Preserve semantic IDs, user geometry, source provenance and editable .drawio files. Review source-backed changes and distinguish imported facts from inferred architecture. Software/cloud/schema importers do not extract Chisel/RTL microarchitecture; provide a verified hardware model manually when needed. Structural and policy checks apply to the modeled graph, not automatically to runtime behavior or deadlock freedom. Render and visually inspect relevant changes. Do not auto-publish, install an MCP server, or enforce extra approval rounds for already authorized local output.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

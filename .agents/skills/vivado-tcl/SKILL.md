---
name: vivado-tcl
description: Generate, review or execute scoped Vivado Tcl automation with explicit
  inputs, checkpoints and verifiable outputs.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Read [REFERENCE.md](REFERENCE.md) for commands and confirm installed-version support. Script generation is distinct from execution. Use exact paths, top/part/configuration and deterministic output locations, with failures propagated rather than caught and labeled success. Execute requested hardware tooling on Alan, inspect existing jobs and preserve unrelated work. Verify process exit plus expected outputs/report content and provenance; loading a checkpoint or a zero exit alone is not functional/timing success. Respect limits on modifying projects, constraints, artifacts and board state.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

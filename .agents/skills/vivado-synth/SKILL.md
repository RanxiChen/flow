---
name: vivado-synth
description: Analyze Vivado synthesis and resource inference, including RAM modes,
  byte enables, DSPs, FSMs and hierarchy.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Read [REFERENCE.md](REFERENCE.md) and only relevant examples. Connect Chisel structure and emitted RTL to inferred memories, muxes, registers, DSPs and critical fanout. For cache arrays, verify read latency, read-during-write behavior, port sharing, write masks and reset effects against the target primitive. Run requested synthesis on Alan. Treat synthesis timing as an estimate; verify mapping from reports rather than source syntax or defaults. Preserve interfaces and behavior when optimizing.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

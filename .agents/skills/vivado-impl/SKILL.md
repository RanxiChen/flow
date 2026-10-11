---
name: vivado-impl
description: Plan or run Vivado placement, routing and physical optimization with
  checkpoints, congestion diagnosis and candidate comparisons.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Read [REFERENCE.md](REFERENCE.md) and relevant examples. Establish device, constraint, netlist, tool, implementation stage and seed. Use stage-compatible directives and preserve baseline/best checkpoints. Keep immutable candidate inputs and reports. Physical optimization may change the netlist despite unchanged RTL; respect the requested scope and validate applicable behavior. Analyze existing reports when execution is not requested. Report post-place estimates separately from routed timing, and do not generate/program a bitstream as an automatic next step.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

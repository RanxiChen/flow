---
name: vivado-sim
description: Choose and debug Vivado behavioral, netlist or timing simulation and
  activity capture for an existing hardware design.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Read [REFERENCE.md](REFERENCE.md) for the installed-version flow. Prefer existing Breeze module tests for functional regressions; choose xsim/netlist/SDF when it addresses the actual question. Match emitted RTL, simulation libraries, clock/reset and source identity; run on Alan. Separate RTL functional evidence, synthesis equivalence questions, SDF simulation and static timing checks. Timing simulation does not establish full static timing closure. Capture SAIF/VCD only when power/activity analysis is in scope.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

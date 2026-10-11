---
name: gf-plan
description: Plan hardware modules, interfaces, pipelines, FSMs and verification before
  implementation; use for architecture or RTL planning requests.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Use [design patterns](references/design-patterns.md) when a concrete circuit pattern is needed and [build/tool guidance](references/build-and-tools.md) for implementation planning.

Establish requirements, authority, target and existing integration points. Separate accepted design decisions from current implementation and pending choices. Describe normal flow, concurrency, conflicts, backpressure, recovery/kill and the irreversible side-effect boundary. Specify interfaces by acceptance, ownership, response, cancellation and ordering rules, not only port widths.

Produce the requested design note with stage/FSM tables, resource ownership, relevant diagrams, implementation dependencies and validation criteria. Use [breeze-microarchitecture-review](../breeze-microarchitecture-review/SKILL.md) for CPU-specific mechanisms. Quantify latency and throughput separately, including stalls. Do not assign arbitrary coverage percentages, fixed reset cycles, reset every memory bit, or add buffers merely because a template contains them.

A planning request ends with the reviewable plan and open decisions; it does not auto-start code generation or launch GateFlow agents. Use native Chisel Bundles/parameters for Breeze plans rather than converting the project to SystemVerilog.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

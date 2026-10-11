---
name: breeze-spec-verification
description: Map Breeze architecture and RTL requirements to tests, assertions, reference-model
  behavior and separately scoped validation evidence.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Identify the authoritative requirement and current source/configuration before mapping evidence. Resolve contradictions explicitly; preserve a design-only mode when the implementation is pending. Use [spec-to-testplan](../spec-to-testplan/SKILL.md) for general extraction and output templates.

Create a requirement matrix with ID/source, stimulus/precondition, expected visible result, relevant assertion/test, environment assumption, implementation mapping and evidence status/path. Derive expected results independently from the DUT. Distinguish coverage intent, tests present, tests executed and results passing; counts alone do not establish requirements coverage.

For memory mechanisms, include accepted-request accounting, data/byte masks, dependency conflicts, ordering, TLB/PTW and cache misses, permission/PMP/PMA faults, stale array reads, probes/refills, backpressure and cancellation as relevant to the module contract. Include same-edge combinations and boundaries such as queue-full arrival, return plus kill, or write plus read. Specify legal environment fairness before claiming progress; include reachability checks to avoid vacuous assertions.

Reuse existing Chisel tests and assertions where suitable; use generated RTL with matching provenance for Cocotb/formal. Run hardware validation on Alan only within execution scope. Archive exact commands, source/config/tool identities, seeds, exits and result artifacts. Keep module, integration, synthesis, routed timing and board/runtime evidence separate. Update the matrix only for evidence actually obtained.

---
name: breeze-microarchitecture-review
description: Review Breeze CPU and memory mechanisms for concurrent flow, hazards,
  recovery, commit boundaries, resource ownership and timing tradeoffs before RTL
  changes.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Start from the user's proposed mechanism and the designated current design documents. For D-cache/MMU, inspect docs/dcache-pipeline-design.md, docs/breeze-mmu-rtl-spec.md and docs/breeze-mmu-closure-checklist.md only as relevant. Recheck their current contents; do not bake historical geometry, queue depths or latency into this skill.

Review one mechanism at a time. Explain a concrete normal transaction, then the smallest overlapping/conflicting scenario and its recovery. Track each transaction's age, address-known state, resource ownership, query snapshot validity, side effects and response destination. Separate query execution order, write visibility and architectural completion order.

Check interfaces for acceptance, holding, cancellation, response capacity and ordering; check memory arrays for ports, masks, simultaneous access and stale snapshots. For load/store hazards, distinguish RAW/WAW/address uncertainty, coherence/refill invalidation and SRAM port conflicts. For PTW, trace forward progress through the full wait-for chain (CPU/TLB/PTW/D-cache/L2/probe/response), explicitly identifying reserved capacity or independent egress. Analyze store commit/kill and exception boundaries before allowing irreversible writes.

Compare feasible mechanisms by throughput, latency, recovery cost, storage/compare/mux logic and critical paths. Introduce scoreboards, forwarding, store buffers, nonblocking misses or bank parallelism only if requested or justified by the current goal; no template makes them mandatory. FIFO capacities need a cycle-by-cycle bound including same-edge arrivals and backpressure, not a guessed depth.

Deliver the agreed behavior, illustrative cycle table/diagrams, resource/wait relationships, alternatives and genuinely unresolved choices. Use [breeze-spec-verification](../breeze-spec-verification/SKILL.md) to derive checks. A review does not authorize edits or substitute for RTL/simulation/PPA evidence.

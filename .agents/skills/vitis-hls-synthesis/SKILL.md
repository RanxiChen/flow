---
name: vitis-hls-synthesis
description: Analyze or synthesize C/C++ HLS kernels with Vitis, including pipeline
  initiation interval, interfaces, memory ports and co-simulation.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Use only for C/C++ HLS tasks; do not route Breeze Chisel RTL work through HLS. Read [REFERENCE.md](REFERENCE.md) and task-specific references/examples as needed. Establish numerical/overflow contracts, array layout, target, tool and resource/clock budgets before pragmas. Explain latency versus initiation interval and memory-port bottlenecks. Run requested csim/csynth/cosim/implementation on Alan, preserving evidence for each stage. HLS estimates do not prove routed timing or system throughput; do not introduce automatic design-space agents.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

---
name: vivado-analysis
description: Interpret Vivado timing, utilization, congestion, CDC and methodology
  reports; diagnose critical paths from existing evidence.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Analyze existing reports in read-only tasks. Identify report stage, path group, clocks, launch/capture pins, constraint requirement, data/clock contributions, logic depth, fanout and routing share. Map the path to matching emitted RTL and Chisel source where available. Read [REFERENCE.md](REFERENCE.md) for report commands. Use [breeze-timing-evidence](../breeze-timing-evidence/SKILL.md) when comparing candidates. Report missing evidence without inventing numbers or forcing a build.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

---
name: vivado-debug
description: Plan or use Vivado ILA, VIO and hardware-debug captures for a specified
  FPGA failure and trigger hypothesis.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Read [REFERENCE.md](REFERENCE.md) and [troubleshooting](references/debug-troubleshooting.md) as needed. Establish a concrete symptom, board/bitstream identity, clock/reset domains, proposed signals and trigger before instrumentation. Choose capture depth and probes to distinguish hypotheses, including handshake acceptance and transaction identity. Instrumentation consumes resources and may change timing; record both. Analysis or instrumentation does not itself authorize board programming, VIO writes or bus transactions. Report observed captures separately from inferred causes.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

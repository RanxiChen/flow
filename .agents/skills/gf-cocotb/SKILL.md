---
name: gf-cocotb
description: Build self-checking Cocotb tests for emitted RTL with protocol drivers,
  randomized backpressure, reference models and reproducible failures.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Use the repository's existing test framework where it fits; use Cocotb when requested or when external protocol drivers/reference models justify it. Identify emitted RTL, top, clock/reset, simulator and installed Cocotb version first. Create and run hardware builds/simulation on Alan within the requested scope.

Read the retained upstream templates for runner and protocol-library ideas, then adapt to the installed API. Drive and sample in defined simulation phases (including ReadOnly when appropriate), and count accepted transfers from valid/ready at clock edges. Do not rely on template RisingEdge-only sampling or hardcoded queue depth. A stimulus-only test is not a self-checking test.

Use an independent expected-result model, monitors and a verification scoreboard. This scoreboard belongs to the testbench; it does not imply a hardware scheduling scoreboard. Check loss, duplication, data/mask correctness, ordering, exception and kill semantics. Exercise random stalls plus targeted conflict/recovery cases, using recorded seeds and bounded timeouts. Preserve the failing seed/waveform and reduce it before fixing the DUT. Report the exact tests run and their scope.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

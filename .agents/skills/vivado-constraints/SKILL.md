---
name: vivado-constraints
description: Review or author Vivado XDC for clocks, I/O timing, CDC and justified
  timing exceptions tied to interface contracts.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Read [REFERENCE.md](REFERENCE.md) for commands. Identify actual clocks, generated relationships, external device timing and synchronization protocols before changing constraints. Derive I/O delays from board/interface evidence. Justify false paths, multicycle setup/hold and clock groups by the real protocol; exceptions do not repair CDC hardware. Analyze missing constraints in read-only requests. Verify requested changes using check_timing, clock interaction, CDC and exception reports from matching Alan builds; do not guess pinout or suppress failing paths.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

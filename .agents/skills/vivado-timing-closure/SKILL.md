---
name: vivado-timing-closure
description: Optimize Vivado timing within fixed design constraints, compare implementation
  candidates and preserve the best measured result.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Read [diagnosis](references/diagnosis-guide.md), [methodology](references/methodology-checklist.md) and [optimization](references/advanced-optimization.md) as needed. Establish a matching source/config/tool/constraint baseline. Separate RTL pipeline changes from placement/routing tactics. Run only authorized candidates on Alan, retaining checkpoints and consistent setup/hold/pulse-width/resource checks. Stop at the requested result or overall attempt/time budget; a strategy plateau calls for reassessment, not a fabricated pass. Generic improvement percentages are not predictions. Use the requested timing margin, not a universal positive-margin gate. Do not label 1000/(period-WNS) a universal Fmax: it is at most a qualified estimate for a suitable same-clock setup path, not I/O, multicycle or cross-clock paths. Prefer actual closure at the requested clocks.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

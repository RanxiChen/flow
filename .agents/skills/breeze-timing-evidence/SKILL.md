---
name: breeze-timing-evidence
description: Bind Breeze timing and resource claims to matching source/config/tool
  reports and evaluate pipeline changes from measured critical paths.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

For a report-only question, inspect existing evidence without forcing a new build. Identify source SHA (or explicit working-tree snapshot), top/configuration, target part, tool version, constraints, clock definitions, report stage and source/report correspondence. Historical or unmatched reports may guide hypotheses but cannot establish current timing.

For each relevant path, record start/end pins and clocks, path group/type, requirement and slack, logic/routing delay, logic depth, fanout and timing exceptions. Map emitted RTL to Chisel where supported. Distinguish comparator, priority mux, word/way selection, PMP/PMA, ready/CE and placement bottlenecks. Do not infer that PMP needs another stage solely from a stale path containing translation or a high-fanout enable.

For candidate comparisons, preserve the baseline, keep matched configuration/constraints/tool, and record actual latency/throughput/interface changes alongside resources and all applicable timing metrics. Run requested build/implementation on Alan after checking branch/HEAD/worktree and existing jobs. State post-synthesis/post-place estimates separately from routed timing and board results. Synthesis success and positive WNS alone do not prove functional integration or Linux boot.

Deliver a compact evidence table, critical-path explanation, bounded hypotheses and an actionable next measurement. Do not promise Fmax or improvement percentages from a template. Do not weaken constraints or change target frequency merely to label a candidate passing; honor the user's acceptance target.

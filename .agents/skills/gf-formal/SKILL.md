---
name: gf-formal
description: Specify and check local RTL safety, bounded behavior and liveness properties
  with SymbiYosys; explain assumptions, proof scope and counterexamples.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Start with a property matrix: requirement, DUT boundary, assert/assume/cover, reset semantics, environment obligations, proof mode and expected evidence. For Breeze, identify the Chisel source and exact emitted RTL/configuration used by the harness. Generate RTL or run EDA checks on Alan only when execution is requested.

Read [proof strategy](references/engines-and-strategy.md), [configuration patterns](references/sby-templates.md), [assertion patterns](references/sva-patterns.md) and [counterexamples](references/counterexamples.md) as needed. Check installed Yosys/frontend support before using concurrent SVA; lower unsupported constructs or use an available frontend, and report tool limitations rather than a pass.

Useful local properties include queue bounds, exclusive grants, accepted-request accounting, payload stability under backpressure and suppression of canceled side effects. Choose persistence rules from the actual interface contract: Chisel Decoupled alone does not establish Irrevocable behavior. Do not constrain away legal inputs to obtain a proof. Distinguish caller assumptions from properties the DUT must enforce; include reachability covers and check reset/assumption vacuity.

Record BMC depth, induction/unbounded result, timeout/unknown/error, tool versions, configuration and source identity separately. A bounded pass is not an unbounded proof. Progress properties need explicit environmental fairness (for example, eventual L2 response); a cover witness does not prove eventual completion. Inspect counterexamples before changing RTL or adding assumptions. No fixed agent names or plugin orchestrator are required.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

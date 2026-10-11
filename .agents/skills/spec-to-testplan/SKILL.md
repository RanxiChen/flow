---
name: spec-to-testplan
description: Turn a hardware specification and available RTL or Chisel interfaces
  into traceable verification scenarios and a requirement coverage plan.
license: PolyForm-Noncommercial-1.0.0
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Read the designated specification before implementation evidence. A design-only request may use a spec without RTL: mark implementation mapping pending rather than blocking analysis. Resolve source authority from the user and project rules; do not let historical prose override a designated RTL specification.

Use [output schema](references/output-schema.md), [testplan template](references/testplan-template.md) and assets/templates only when that output is requested. For Verilog/SV, scripts/parse_rtl_structure.py provides regex-based structural hints; it is not an elaborator and cannot prove completeness or evaluate every parameter. Never apply it to Scala. For Chisel, inspect Modules/Bundles/Enum/Decoupled definitions directly or use already emitted RTL with matching provenance.

Derive expected behavior and scenarios from requirements. RTL may supply interface, state and implementation evidence for discrepancy analysis; do not copy DUT logic into the golden model. This replaces the contradictory upstream restriction on reading internal logic while extracting FSMs.

For each requirement, record its source, expected result, legal environment assumptions, directed/random test or assertion, boundary/conflict/recovery cases, and current evidence status. Distinguish proposed tests from executed coverage. Include reset, backpressure, cancellation, exceptions, masks/alignment and response ordering where relevant. Read [breeze-spec-verification](../breeze-spec-verification/SKILL.md) for Breeze-specific traceability. Generate only requested analysis artifacts; do not automatically start UVM, builds or code changes.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.

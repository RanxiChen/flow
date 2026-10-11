---
name: hardware-wavedrom
description: Describe and render cycle-accurate hardware scenarios using WaveJSON,
  including valid/ready, pipeline occupancy, stalls, replay and side effects.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Use the [official WaveDrom tutorial](https://wavedrom.com/tutorial.html) and [WaveJSON schema](https://github.com/wavedrom/schema/blob/master/WaveJSON.md) for syntax. Keep strict JSON source when practical so ordinary JSON tools can validate it. Detect installed WaveDrom CLI/editor support; use existing tooling or provide source without claiming an unperformed render. Dependency installation is a separate action when needed.

Choose one concrete scenario and define the cycle convention first: values during T, acceptance at the T-ending edge, register contents during T+1. List input transactions, stage occupants, array read/write events, address/translation availability, response acceptance and side effects. Derive valid/ready/fire from the actual interface contract. Encode bus values only when meaningful and mark bubbles/unknowns honestly.

For cache scenarios, include simultaneous older Store and younger read, read-during-write policy, TLB miss/PTW arbitration, cache refill, kill and response backpressure as relevant. Label illustrative latencies and environment assumptions. Compare the WaveJSON to the cycle table before rendering; rendering does not detect semantic errors. Do not change pipeline decisions to simplify the drawing. Use Mermaid for abstract interaction sequences and WaveDrom for exact cycles.

Deliver the scenario assumptions, cycle table, editable .json and rendered SVG if available, with any unresolved design behavior.

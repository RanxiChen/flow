# Tandem Trace Framework Notes

## Overview

This document summarizes the current tandem-trace related work in the Breeze core and simulation framework.

The implementation today is **not yet a full tandem verification framework**. It is a reusable template layer that:

- exports per-commit hardware trace information at WB
- captures those commit events in Scala simulation
- provides both a raw event layer and a semantic parser layer
- can print commit logs during simulation for debug

The current goal is to make commit behavior observable and debuggable first, so that later work can evolve toward:

- Spike-compatible log formats
- commit-by-commit comparison against a reference model
- a real tandem verification flow instead of the current generic scaffold

## What Is Implemented

### 1. Hardware Commit Trace Export

The core now exposes a commit-oriented trace payload when tandem mode is enabled.

This trace is exported from the WB stage, which is treated as the single commit point.

The exported raw fields currently include:

- `valid`
- `pc`
- `inst`
- `nextPc`
- `estop`
- `rdWriteEn`
- `rdAddr`
- `rdData`
- `memEn`
- `memIsWrite`
- `memAddr`
- `memAlignedAddr`
- `memRData`
- `memWData`
- `memWMask`

These fields are carried through the pipeline and completed incrementally:

- base instruction identity fields are prepared from earlier stages
- `nextPc` is completed when branch/jump outcome is known
- memory-related fields are completed from existing memory request/response signals
- WB only acts as the final export point

This avoids rebuilding writeback, branch, or store-mask logic a second time.

### 2. Scala Raw Event Layer

On the simulation side, the raw hardware event is represented by:

- `RawCommitEvent`

This type is intentionally close to the hardware-exported signal bundle. It is the lowest-level software representation of a hardware commit.

There is also a tandem-aware simulation result wrapper:

- `BreezeCoreSimTandemResult`

This wrapper keeps the original simulation result intact and adds:

- `commitEvents: Seq[RawCommitEvent]`

The original `BreezeCoreSimResult` was deliberately left unchanged.

### 3. Semantic Commit State Layer

A second layer was added above `RawCommitEvent`:

- `CommitEffects`
- `CommitUpdate`
- `RawCommitEventParser`

This layer is intended to represent a more semantic notion of what a committed instruction changed in system state.

Current semantic objects include:

- `RegWrite`
- `MemRead`
- `MemWrite`
- `Redirect`

and the parser builds:

- `CommitUpdate`

from each `RawCommitEvent`.

### Important Note

This semantic layer exists, but it is **not currently the main path used for logging**.

It was added as a preparatory abstraction so that later comparison logic does not need to operate directly on raw hardware fields.

In other words:

- `RawCommitEvent` is the currently active practical layer
- `CommitUpdate` is the currently prepared semantic layer for future tandem work

### 4. Simulation-Side Event Capture

The simulation framework can now capture commit events directly from the hardware-exported WB trace.

This is done in the simulation runner:

- when the tandem trace output is enabled
- and `valid` is observed high on a cycle
- a `RawCommitEvent` is constructed and appended to the result sequence

At this stage:

- events are collected
- no reference model comparison is performed

### 5. Commit Log Output

The simulation framework now supports explicit commit logging.

The log is based directly on `RawCommitEvent`, not on the semantic parser layer.

This was a deliberate choice to keep the current debug path simple and close to the real hardware-exported event.

Each committed instruction can be printed as a single log line containing:

- the cycle where the commit was observed
- `pc`
- `inst`
- `nextPc`
- bus read information if present
- bus write information if present
- register write information if present
- redirect information if present
- `estop` if present

For example, a load may show both:

- a bus read
- a register write

in the same commit line, because both effects belong to the same retiring instruction.

## JSON-Controlled Simulation Logging

The existing simulation JSON already provides:

- memory map
- boot address

It now also supports an explicit tandem logging switch under the `simulation` section:

```json
{
  "simulation": {
    "bootaddr": "0x80",
    "tandemLog": true
  },
  "memoryMap": {
    "0x80": "0x0000000000000013"
  }
}
```

Behavior:

- `tandemLog = false`
  - run the normal simulation path
  - do not print commit logs

- `tandemLog = true`
  - enable tandem trace export
  - collect raw commit events
  - print per-commit logs
  - report `commitCount` and `ipc` in the final simulation summary

## Current Final Simulation Summary

When tandem logging is enabled, the final simulation summary now reports:

- `cycleCount`
- `commitCount`
- `ipc`
- `timedOut`

This is meant as a lightweight high-level summary of execution progress.

## Current Limitations

The current implementation is still intentionally lightweight.

It does **not** yet provide:

- reference-model execution
- commit-by-commit comparison
- Spike-compatible log formatting
- exception/trap comparison semantics
- CSR/state comparison beyond the current minimal commit fields
- a full tandem checker

The current framework should be understood as:

- a raw commit export path
- a simulation capture path
- a prepared semantic abstraction layer
- a practical commit logging tool

## Why There Are Two Layers

There are now two software-facing representations:

### Raw Layer

- `RawCommitEvent`

Purpose:

- preserve what hardware exported
- keep logs close to the hardware signals
- minimize interpretation at capture time

### Semantic Layer

- `CommitUpdate`

Purpose:

- prepare for later comparator/reference-model work
- avoid operating on raw hardware field bundles in future tandem logic
- make it easier to change the raw trace format later without rewriting higher-level logic

Right now, only the raw layer is actively used for logging.

## Recommended Next Evolution Steps

The framework can evolve in two main directions.

### 1. Spike-Compatible Logging

One possible next step is to add a formatter that converts the raw commit event into a log layout closer to Spike or other widely used trace formats.

This would make:

- side-by-side visual comparison easier
- external tooling integration easier
- transition toward tandem comparison smoother

The important point is that this should ideally be implemented as:

- a new formatter layer
- not as a rewrite of the raw capture path

### 2. Real Tandem Verification

A later step is to turn the current scaffold into a real tandem framework:

- run the DUT
- run a golden/reference model
- convert both into a comparable semantic update representation
- compare commit-by-commit

At that point, the existing semantic parser layer becomes more important.

## Practical Position of the Current Work

The current system is best thought of as:

> a generic tandem-ready commit tracing template, not yet a full tandem verification implementation

It already provides enough structure to:

- inspect commit behavior
- debug failing programs
- collect commit traces
- build future comparison layers on top

but it intentionally stops short of full tandem checking.

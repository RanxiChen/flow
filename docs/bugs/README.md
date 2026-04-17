# Bug Tracking Notes

## Goal

This directory stores lightweight bug records for issues that are important enough to:

- assign an ID
- preserve a failing reproduction
- track evidence while debugging
- link tests, commits, and fixes together

The intent is not to build a heavy process. The intent is to make debugging reproducible.

## ID Rules

Use a short subsystem prefix plus a sequence number.

- `FE-001`: frontend bug
- `FE-002`: frontend response/PC context mismatch bug
- `CACHE-001`: cache bug
- `CORE-001`: core bug
- `TB-001`: testbench or infrastructure bug

If a bug is first exposed in one subsystem but may be rooted in another, keep the ID simple and explain the possible root cause in the bug note.

## Minimal Record Template

Each bug note should contain:

- `ID`
- `Title`
- `Status`
- `Symptom`
- `Expected`
- `Repro`
- `Evidence`
- `Current Hypothesis`
- `Related Files`

## Repro Path

For this project, the normal reproduction path is:

1. Write or identify a test case that reliably exposes the bug.
2. Run that test case with a fixed command.
3. Observe the failure, logs, and waveforms.
4. Preserve that failing state with a checkpoint commit if useful.
5. Fix the RTL or testbench issue.
6. Re-run the same test case.

If the bug is fixed correctly, the exact same repro command should stop failing.

## Commit Style

Recommended commit message style:

- `FE-001 checkpoint: preserve failing reproduction`
- `FE-001 test: add failing reproduction case`
- `FE-001 debug: expose internal signals`
- `FE-001 fix: align cache request timing`

This makes it easy to reconstruct the whole debugging history with `git log --grep`.

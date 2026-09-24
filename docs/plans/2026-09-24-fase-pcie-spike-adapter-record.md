# FASE PCIe / Spike adapter work record

Date: 2026-09-24

## Decisions

- Preserve the current PCIe FASE user BAR protocol as **ABI v1**. Future
  higher-performance transports may be added, but ABI v1 remains available as
  a compatibility and recovery path.
- Develop the new FASE software in a fresh GitHub clone on the local machine.
  The local clone is used for reading, editing, testing, and committing.
- Create a separate fresh clone on the PCIe Host. The Host consumes an exact
  pushed commit and is used for compilation and hardware-side validation; it
  is not an independent source of edits.
- First implement the minimum adapter and basic ports against PCIe ABI v1.
- Because the current FASE hardware may still contain bugs, implement an
  equivalent Spike backend for functional development and protocol-semantic
  validation. Spike equivalence validates command semantics, not PCIe timing,
  CDC, reset recovery, or FPGA hardware correctness.

## Evidence boundary

- The existing PCIe r3 path has prior board evidence, but hot-reset recovery is
  not established.
- The newly observed working link makes mechanical seating or incomplete power
  removal / power sequencing plausible contributors to the earlier link loss.
  This is a hypothesis, not a confirmed root cause.
- No new FASE repository has been cloned yet.
- No adapter or Spike backend has been implemented yet.
- No RTL change, FPGA programming, or destructive board test is authorized by
  this record.

## Intended workflow

1. User supplies the exact FASE GitHub repository URL and desired branch.
2. Clone locally and on the PCIe Host into newly named directories.
3. Record repository URL, branch, initial commit, clone paths, and Host route.
4. Inventory the upstream FASE API before fixing the adapter contract.
5. Add a transport-neutral command interface, then a PCIe ABI v1 backend.
6. Implement the same command interface in Spike.
7. Test the common semantics against Spike before controlled hardware tests.
8. Push reviewed local commits; Host pulls the exact commit and compiles.

## Open inputs

- Exact FASE GitHub URL and starting branch/tag/commit.
- Desired local clone path and Host clone path.
- Exact definition of the first basic ports after inspecting upstream FASE.
- Spike source repository/version and whether the backend should be upstreamed
  into Spike or built as an external integration layer.

# Parallel translation and L1 array lookup

MMU-enabled Breeze cores start a read-only L1 array lookup while their
instruction/data translation request is active. The FPGA/Linux configuration
enables this on both L1I and L1D. Non-MMU configurations keep the existing ports.

## Request sequence

1. The translation adapter holds the virtual address and independently offers
   it to the MMU and to the cache's `arrayReq` port. Array-port backpressure
   does not block translation. Each translation attempt issues at most one
   accepted early array read.
2. The cache uses the page-offset index to read all ways of tag/data SRAM.
   One cycle after the SRAM read, it captures their outputs in registers.
   This read does not allocate a miss, change replacement/coherence metadata,
   write data, issue MMIO, or return an architectural result.
3. After translation and PMP authorization, the adapter sends the existing
   physical request. If the saved array contents are still valid for its set,
   the cache suppresses the redundant SRAM read and uses the saved contents
   in its existing tag-comparison stage. Tags are compared against the
   **physical** address, including when virtual and physical page numbers differ.
4. Without a usable snapshot, the physical request follows the normal SRAM
   read/lookup path. Translation faults never issue this physical request.

L1D uses index `[10:5]` with 64 sets and 32-byte lines. L1I's parallel mode
requires `sets * lineBytes <= 4096`. Thus all virtual index bits are inside
the smallest Sv39 page's unchanged offset; no virtual-page alias management
is needed for this geometry. Physical PMA classification still gates final
cache behavior, including uncached/MMIO accesses and writes/atomics.

The snapshots add all-way tag/data storage and a result mux. Their area and
timing cost needs measurement; this change does not claim a measured Fmax or
IPC improvement. The existing registered physical-request and tag-comparison
stages remain: overlapping the SRAM read alone does not establish that a
TLB/cache hit takes fewer cycles.

## Progress, invalidation and replay

The early L1D lookup never owns the blocking CPU transaction slot. Physical
PTW requests remain able to use L1D while a CPU translation is waiting. Any
physical request, probe, flush or non-idle cache state invalidates the saved
snapshot. Consequently a page walk or a coherence intervention can force the
translated CPU request to re-read the arrays. It must never consume data saved
before such an intervention. Once a physical request has selected a snapshot,
the existing bounded Lookup/atomic window protects that transaction.

L1I invalidates its snapshot on flush, a real request or active lookup/refill.
Ordinary redirect cancels the translation adapter's request; an unused snapshot
is only read-only cache contents, and can be reused solely by set and physical
tag validation. Replacing a snapshot clears its valid bit immediately, before
the new synchronous read completes, to avoid pairing a new index with old data.

## Page-table request timing boundary

`ReadPteReq` and `UpdateAdReq` perform PMP checks and register the complete
authorized physical request. New `ReadPteSend` and `UpdateAdSend` states drive
`memReq` from that register and hold it until `fire`. PMP `allowed` no longer
combinationally controls the core's physical-port address selection in Send.
Each permitted PTE read or A/D update gains one cycle without downstream stall;
TLB hits do not traverse these added states. Existing killed-walk drain behavior
is retained, including suppressing canceled translation responses.

## Verification

`BreezeParallelLookupSpec` observes actual SRAM read enables as well as returned
data. It checks early reads, reuse without a second read, physical tags with
different virtual addresses, load/store/AMO, PTW progress, probe invalidation,
flush, PMA denial, and replacement of a snapshot before its read completes.
`BreezeParallelTranslatorSpec` checks concurrent translation/array requests,
independent backpressure, denied stores, and instruction redirect cancellation.
Existing MMU/PMP and cache regressions check the retained physical paths.

Local validation on 2026-09-11: **46/46 tests passed** across seven suites:
parallel array lookup (2), MMU (3), parallel translation adapters (2), shared
PMP (1), I-cache (3), D-cache set associativity/MMIO (12), and D-cache coherence
and atomics (23). The new parallel-mode tests check SRAM controls directly;
the older cache suites retain their default configuration and provide
compatibility coverage, not additional parallel-mode coverage.

Commands, run from `design/`:

```sh
sbt 'testOnly flow.cache.BreezeParallelLookupSpec flow.mmu.BreezeMmuSpec'
sbt 'testOnly flow.mmu.BreezeParallelTranslatorSpec flow.mmu.BreezePmpSharingSpec flow.cache.BreezeCacheSpec flow.cache.BreezeDCacheSetAssocSpec flow.cache.BreezeDCacheCoherentSpec' 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone single gshare linux fpga-debug'
```

The single-hart Linux/GShare/FPGA-debug top elaborated successfully; generated
L1I/L1D RTL includes both `arrayReq` ports and snapshot selection. Elaboration
reported 27 single-hart dynamic-index-width warnings in the unchanged L2/Home
and MMIO arbiter modules. The second command's local log is
`/tmp/flow-vipt-validation.log`; individual results are under
`design/target/test-reports/`. This is local functional/elaboration evidence,
not an Alan Linux boot or a Vivado implementation result.

The report in `fpga/kcu105/reports/` predates this change. A new Vivado run is
required before claiming that the 100 MHz timing target is met.

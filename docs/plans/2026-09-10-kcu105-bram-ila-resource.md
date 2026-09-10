# BRAM baseline and register-file storage optimization

## Measured baseline

Source: `cbcee530960d0aae51902c9b2fe54006bf21a0a9`.
Alan run: `/home/chen/FUN/flow/build/fpga/kcu105-pmp8-shared-bram-50mhz-20260910-cbcee53`.
Four harts, 50 MHz, eight active PMP entries, shared checker per MMU,
256 KiB on-chip main RAM. Vivado completed in 57:46.24 wall time.
Routed timing: WNS +1.604 ns, WHS +0.030 ns, no timing violations or route conflicts.
The user reported BIOS memory test, integer, memory, floating-point, and
two-/four-hart atomic-counter and payload-ring tests passing on this bitstream.

The routed checkpoint reports:

| Resource | Used | Available | Utilization |
| --- | ---: | ---: | ---: |
| CLB sites | 29,444 | 30,300 | 97.17% |
| LUTs | 175,725 | 242,400 | 72.49% |
| FFs | 95,829 | 484,800 | 19.77% |
| BRAM tiles | 252.5 | 600 | 42.08% |

Only 856 CLB sites are unoccupied. Spare LUT and BRAM totals alone cannot
establish that an ILA will place and meet timing.

Primitive `LOC` attribution from the routed checkpoint, aggregated over all
four harts (including top-level data caches and L2):

| Group | CLB sites touched | Sites exclusive to this group |
| --- | ---: | ---: |
| FPU | 8,492 | 3,719 |
| CSR file | 5,765 | 2,075 |
| Integer multiplier | 5,431 | 1,513 |
| Data caches | 5,019 | 2,258 |
| Frontends | 5,018 | 1,443 |
| MMUs | 4,819 | 838 |
| FP register files | 3,330 | 545 |
| Integer register files | 3,056 | 609 |
| L2 home | 2,683 | 739 |

These are overlapping sets, not additive area totals. There are 14,880 sites
shared by multiple groups. Exclusive sites describe this placement; they do
not predict how many sites an edit will free after replacement and rerouting.
Hierarchy names also reflect cross-boundary optimization, not exact source
ownership of all logic.

Reports extracted without rerunning implementation:
`/tmp/flow-clb-cbcee53.log`,
`/tmp/flow-clb-cbcee53-utilization-route.rpt`, and
`/tmp/flow-clb-cbcee53-hierarchy-route.rpt` on Alan. Temporary local text copies
are under `build/fpga/resource-analysis-cbcee53/`.

## Selected change

FPU is the largest group, including its substantial divide/square-root logic.
This pass targets a separate, clear storage-mapping inefficiency: the integer
and FP register files together use 12,128 LUTs and 16,128 FFs, with no LUTRAM.
Their resettable `RegInit(Vec(...))` data arrays force flip-flop storage and
large asynchronous read multiplexers.

Replace these arrays with asynchronous-read, synchronous-write `Mem` arrays.
Only per-register validity bits reset. Reads of unwritten entries return zero,
so visible reset behavior is preserved even though RAM contents are not reset.
Integer x0 remains zero; FP f0 remains writable. Preserve both integer read
ports, all three FP read ports, and write-through bypass. Do not add a read
cycle, reduce hart count, or change FPU arithmetic/pipeline configuration.

The intended mapping is distributed RAM. Actual LUTRAM inference, CLB savings,
and timing must be checked in the new integrated Vivado run. No ILA has been
inserted yet; its width/depth and final fit remain to be validated separately.

## Validation

`BreezeRegisterStorageSpec` checks all register addresses, seeded arbitrary
64-bit data, asynchronous reads without intervening clocks, disabled writes,
same-cycle bypass, writable f0, x0, and reset after prior writes (including a
write request held during reset).

Local validation passed on 2026-09-10: seven ScalaTest cases across the
following invocations, plus production four-hart RTL generation:

```sh
cd design
sbt 'testOnly flow.core.RegFileSpec flow.core.BreezeRegisterStorageSpec flow.fpu.BreezeFpUnitSpec' \
    'runMain flow.top.GenerateBreezeMulticoreClusterWishbone small gshare linux production'
sbt 'testOnly flow.backend.BreezeBackendFpSpec -- -z "dependent FP64"' \
    'testOnly flow.core.BreezeCoreSpec -- -z "dependent add/sub"'
```

The generated storage modules contain reset-free 32x64 arrays, two/three
asynchronous read ports, and one edge-triggered write port. Integrated
backend/core tests preserve dependent FP64/FMA results and integer dependency
chains without additional pipeline stalls.

Use the normal local commit/push, Alan pull, elaborate and Vivado workflow.
Re-run the existing demo `all` on the new bitstream before calling the
hardware change validated. Vivado mapping and post-route timing results for
the new change are pending.

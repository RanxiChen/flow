# Current FPGA implementation results

Configuration: KCU105, breeze-tiny (one hart), DDR, Tandem and ILA enabled,
requested system clock 100 MHz (reported timing period 9.931 ns), Vivado 2022.2.

Result: bitstream and debug probes generated, but timing FAILED.
Run: 2026-09-11 09:54:29 to 10:36:05 Asia/Shanghai.
Vivado wall time: 2496.574 seconds (41 min 36.574 s), excluding tests and RTL/BIOS generation.

| Metric | Previous run | Current run |
| --- | ---: | ---: |
| WNS (ns) | -1.278 | -0.566 |
| TNS (ns) | -3744.341 | -153.143 |
| Setup failing endpoints | 5309 | 748 |
| WHS (ns) | 0.004 | 0.004 |
| CLB (post-place) | 13364 | 12573 |
| LUT (post-place) | 61995 | 57351 |
| FF (post-place) | 38869 | 39516 |

Timing report is post-route; utilization reports are post-place physical optimization.
The worst reported path is now in the FP64 FMA, from its input pipeline
register to its internal sum register. This does not prove other path classes
have no remaining violations: the summary only expands the worst paths.

Validation: 55 D-cache/L2 tests passed; 7 backend GShare and 2 FPU tests
passed after applying a Verilator 5.028 compatibility configuration for
FPnew's disjoint packed-array stage assignments. Wrapper and ILA checks passed.
The initial 9 FPU/backend tests failed at simulator compilation, not assertions.
Compatibility configuration used only for simulation:

```text
`verilator_config
lint_off -rule BLKANDNBLK -file "*/third_party/cvfpu/src/fpnew_*.sv"
```

These files are replaced with each implementation run; Git history preserves
previous results. Bitstreams and checkpoints remain outside the repository.

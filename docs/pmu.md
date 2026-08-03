# Breeze machine-mode PMU

Breeze implements the RISC-V machine hardware-performance-monitor framework
without adding MMIO registers or custom instructions. `mcycle` and `minstret`
remain the architectural cycle and retirement counters. Eight additional
64-bit counters, `mhpmcounter3` through `mhpmcounter10`, select platform-defined
events through `mhpmevent3` through `mhpmevent10`.

`mcountinhibit` implements `CY`, `IR`, and `HPM3` through `HPM10`. Counter and
selector CSRs above 10 remain read-only zero. Unsupported selector values are
WARL-coerced to event zero, which means no event.

| Event | ID | Counting point |
| --- | ---: | --- |
| none | 0 | no increment |
| control retired | 1 | retired branch, JAL, or JALR |
| control taken | 2 | retired control transfer with `nextPc != pc + 4` |
| prediction miss | 3 | retired control transfer with direction or target mismatch |
| ICache access | 4 | fetch request accepted by the ICache |
| ICache miss | 5 | one refill request allocated, not one Wishbone beat |
| DCache access | 6 | CPU request accepted by the DCache |
| DCache miss | 7 | one cacheable lookup miss |
| DCache uncached | 8 | one PMA device/non-cacheable lookup |
| memory stall cycle | 9 | backend held for a memory request or response |
| load-use stall cycle | 10 | backend load-use interlock asserted |

The MCU runtime uses the default mapping below. It inhibits and clears all
counters before `main()`, starts them immediately before calling `main()`, and
freezes them immediately after `main()` returns. The fixed call/return and stop
instruction are included, so comparisons must use the same runtime.

| Counter | Default event |
| --- | --- |
| `mhpmcounter3` | control retired |
| `mhpmcounter4` | control taken |
| `mhpmcounter5` | prediction miss |
| `mhpmcounter6` | ICache miss |
| `mhpmcounter7` | DCache access |
| `mhpmcounter8` | DCache miss |
| `mhpmcounter9` | DCache uncached |
| `mhpmcounter10` | memory stall cycle |

After freezing the counters, the runtime stores a ten-word snapshot in linker-
reserved SRAM. The LiteX monitor captures those architecturally retired stores
and prints `BREEZE_PMU`; UART is not involved. On FPGA, M-mode firmware can read
and program the same CSRs directly through `breeze/perf.h`.

Useful derived metrics are:

```text
IPC                  = minstret / mcycle
prediction miss rate = prediction_miss / control_retired
ICache MPKI          = icache_miss * 1000 / minstret
DCache miss rate     = dcache_miss / dcache_access
memory stall ratio   = memory_stall_cycles / mcycle
```

ICache events include speculative fetches, including wrong-path requests. Cache
misses count transactions at the cache boundary and never count the four
64-bit Wishbone refill beats as four misses.

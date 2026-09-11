# Breeze ACT 4.0 integration

This directory adapts the official RISC-V Architectural Certification Tests
(ACT 4.0) to Flow. It does not vendor or fork ACT. `ACT4_REV` pins the upstream
branch and commit, while generated sources, ELFs, converted images, and run
logs stay in ignored directories.

## Evidence boundary

The initial enabled build scope is `I`. The UDB file records the currently
identified RV64IMAFDC hardware, but privileged tests are disabled until every
privileged, PMP, interrupt, and virtual-memory parameter has been audited
against RTL. Passing ACT does not replace Linux-specific directed tests.

`BreezeCoreSimApp` is a lightweight direct-core runner. It does not instantiate
the product DCache/L2 memory hierarchy, so it must not be used as architectural
evidence for AMO/LRSC or cache-flush behavior.

The product-path runner is `sim/litex/act4_linux_soc.py`. It reuses the existing
`MulticoreSimSoC` with a selectable single/dual/small GShare/Linux cluster, complete private
L1 caches, shared L2/Home, LiteX address decoder, CLINT, PLIC, LiteUART, and the
256 MiB modeled LiteDRAM. Its reset ROM sends hart 0 to the ACT ELF at
`0x80000000` and parks any secondary harts (none exist with `single`). A passive retirement observer detects
the ELF's `tohost` store; it never drives or backpressures a DUT interface.

The generated LiteDRAM contents are external `sim_mem*.init` files. The runner
therefore compiles one Vsim and replaces only those existing initialization
files between ELFs. A normalized `sim.v` structure hash rejects reuse if
anything other than LiteX's timestamp comments changes.

Latest single-hart result: [330/330 ACT4 and 42/42 directed tests passed on
2026-09-11](../../docs/linux/act4-single-regression-20260911.md).

## Single-hart timing-refactor regression

Use the full-SoC runner for this revision: the direct-core runner bypasses the
L1/L2 hierarchy changed by the timing work. The Makefile defaults `SOC_PROFILE`
to `single` (one physical hart, 16 KiB L2). Python entry points retain `small`
as their backward-compatible default; pass `--profile single` explicitly.
Profile selection controls RTL generation, metadata checks, SoC construction,
and result summaries. The existing hart-zero completion monitor is unchanged.

If rebuilding the ELF corpus instead of reusing it, run `make fetch` and
`make build EXTENSIONS=I,M,Zmmul,F,D,Zca,Zcd,Zicsr,Zifencei,Zaamo,Zalrsc`
inside `verification/act4` first. This run reused the existing 330 ELFs.

```bash
make -C verification/act4 run-linux-soc SOC_PROFILE=single \
  SOC_OUT_DIR=/absolute/path/to/fresh-act4-single-output
```

This runs all ELFs below `ELF_DIR`, regenerates single/gshare/linux/debug RTL,
and builds a fresh Vsim for the first case. Subsequent cases reuse it with
new DDR initialization files. Use a fresh output directory for each source
revision; the existing sim.v hash alone does not fingerprint every external
RTL dependency. Per-case logs stream to disk during the initial build.
Set `SBT` if sbt is not on PATH, and use the Python environment containing
this project's LiteX/Migen/LiteDRAM installation. With Verilator 5.028,
enable the scoped [CVFPU compatibility wrapper](../../sim/verilator/README.md)
for both ChiselSim and the full-SoC build.

The available historical corpus contains 330 ELFs: I 51, M 13, Zmmul 5,
F 82, D 114, Zca 32, Zcd 4, Zicsr 6, Zifencei 1, Zaamo 18, Zalrsc 4.
`include_priv_tests` remains false: ACT success does not establish Sv39 page
walk, interrupt, PMP, or HPM event-boundary correctness. Keep the directed
MMU/cache/CSR/backend tests alongside this architectural regression.

## Commands

```bash
cd verification/act4
make fetch
make build EXTENSIONS=I
make run

# Product-path A extension run: Zaamo + Zalrsc only.
make run-a-linux-soc \
  SOC_A_OUT_DIR=out/linux-soc-a-$(date +%Y%m%d-%H%M%S)
```

Required tools are GCC 15 or newer (`riscv64-unknown-elf-gcc` and
`riscv64-unknown-elf-nm`), the ACT framework dependencies, and Sail RISC-V
0.13.1. `scripts/build_tests.sh` checks these versions before starting a build.
On Alan, the defaults are `/home/chen/opt/act4/gcc-2026.07.15`,
`/home/chen/opt/act4/sail-0.13.1`, and `/home/chen/.local/bin/mise`. Override
them with `ACT4_TOOLCHAIN_ROOT`, `ACT4_SAIL_ROOT`, and `ACT4_MISE_ROOT` when
running elsewhere. The pinned `2026.07.15` binary toolchain currently reports
GCC 16.1.0 and Binutils 2.46, satisfying the GCC 15-or-newer requirement.

Results are written to `out/summary.json`; per-test JSON images and logs are
kept beside it. `FAIL`, `TIMEOUT`, and infrastructure failures are distinct
outcomes.

The full-SoC A runner writes `summary.json`, one log per ELF, the compiled Vsim,
the exact generated SoC, and current LiteDRAM initialization files below
`SOC_A_OUT_DIR`. The first ELF regenerates debug/tandem cluster RTL and compiles
the SoC with at most `SOC_JOBS` host jobs; later ELFs reuse that binary.

The Linux simulation uses 256 MiB at `0x80000000..0x8fffffff`. The ACT linker
and Sail model use the same RAM window, but ACT ELFs are small and do not test
whether a Linux initramfs fits in memory.

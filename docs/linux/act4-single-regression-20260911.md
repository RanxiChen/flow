# Single-hart ACT4 regression, 2026-09-11

Result: **330/330 ACT4 PASS**, zero FAIL, TIMEOUT or INFRA_ERROR. The targeted
regression also passed **42/42 tests** across eleven suites.

This run used source `1416f504fe593a9855ba0da494382456935c747f`, CVFPU
`1b220f3bc89df99e246b72e3574a3a533cf87653`, and pinned ACT4 `dfa582359db885ae4c6ed1fa82faef60874e212c`.
Existing self-checking ACT ELFs were reused; single-hart RTL and Vsim were
rebuilt from the new code. The archived [machine-readable record](../../verification/act4/results/single-20260911.json)
includes every ELF hash, completion cycle count, status, and directed suite.

## Configuration and evidence

The configuration is single/gshare/linux/debug, one physical hart, 8 KiB L1I,
8 KiB L1D, 16 KiB shared L2/Home, 32-byte lines, and modeled LiteDRAM with
256 MiB at 0x80000000. Tandem is enabled for the passive tohost observer.
The reset ROM starts hart 0 directly at the ACT entry point. No Linux kernel
is booted by this runner.

Alan evidence directory: `verification/act4/out/linux-soc-single-20260911-1416f50` (relative to its Flow checkout).
It contains `run.log`, `summary.json`, per-case logs, the compiled SoC,
`provenance.json` with RTL/configuration hashes, and both directed-test logs.
Vsim SHA256: `ee864c8dd0cf7601dd48002767a70b8f692bb38cf8e51d5329d815922384417c`.

| Extension directory | PASS / selected |
| --- | ---: |
| D | 114/114 |
| F | 82/82 |
| I | 51/51 |
| M | 13/13 |
| Zaamo | 18/18 |
| Zalrsc | 4/4 |
| Zca | 32/32 |
| Zcd | 4/4 |
| Zicsr | 6/6 |
| Zifencei | 1/1 |
| Zmmul | 5/5 |

## Directed checks and compatibility

The first directed invocation passed 28 tests but 14 FPU-containing tests
failed during compilation on Verilator 5.028's BLKANDNBLK check for disjoint
packed-array stages. After enabling the file-scoped CVFPU compatibility
configuration, all 14 reran and passed. Thus the final aggregate is 42/42;
the initial compile failures were not functional assertion failures.

Coverage includes CSR/privilege/PMP/Sstc, immediate-model HPM comparisons,
parallel L1 array lookup, translation/MMU/PMP sharing, backend arithmetic,
and GShare training including the three new interrupt-drain cases.

Use the [ACT4 instructions](../../verification/act4/README.md) and
[Verilator compatibility setup](../../sim/verilator/README.md) to reproduce.
The suite invocation was:

```sh
python verification/act4/scripts/run_linux_soc_suite.py \
  --profile single \
  --elf-dir verification/act4/.work/breeze-rv64gc/elfs/rv64i \
  --output-dir verification/act4/out/linux-soc-single-20260911-1416f50 \
  --max-cycles 2000000 --jobs 20 --fresh-build
```

Use a new output directory for a new run. The single-hart RTL elaboration
retained 27 previously known L2/MMIO dynamic-index-width warnings.

## Coverage boundary

ACT privileged tests remain disabled. These 330 passes are not certification
of all privileged behavior or exhaustive Sv39, interrupt, PMP or HPM coverage;
the directed tests complement the ACT corpus. No new Linux boot, board run,
or Vivado implementation was performed. In particular, 100 MHz timing closure
and the post-change CLB/LUT/FF counts remain unmeasured.

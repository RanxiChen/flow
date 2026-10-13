# single-zve32f: 2026-10-13 validation and KCU105 build

## Provenance

- Source: `/home/chen/leisure/flow/.worktrees/zve32f-single`, branch `feat/zve32f-single-20261013`, baseline main `94636c605bdd98bffe2ff7374fc328cd424b1366`. The final commit and tree are recorded in Git; the generated RTL used below came from the same RTL source before documentation and test-only edits.
- Simulation, RTL generation, software build: Alan (`ssh -J clawbot -p 2286 chen@localhost`). Before each compile/generation/simulation, `docs/cross-project/simulation-host.md` was reread. `cloud_chen@47.96.71.231:22` timed out at the 8-second SSH preflight; Alan's activated `flow` environment, SBT, disk, and memory preflight passed. Vivado also ran on Alan as required.
- Remote evidence root: `/home/chen/FUN/flow-runs/zve32f-20261013`. Candidate source: `snapshot-01/src`; generated hardware: `kcu105-01`. CVFPU gitlink `23153ba10a0ab8c6ee774b5e1a928007c3b2adcc`; riscv-tests gitlink `bcffa2b3188b040c611f90dc0b6e422f54775a09`.
- Tool scopes: SBT launcher 1.11.0 / project SBT 1.9.7, Vivado 2022.2, KCU105 `xcku040-ffva1156-2-e` at 100 MHz. The generated gateware source hashes are in `kcu105-01/source-snapshot/manifest.json`.

## Result

| Gate | Command / evidence | Result |
|---|---|---|
| Zve program build | `make -C tests/cluster build/zve_smoke.elf` | exit 0, `rv64gc_zve32f`, `lp64d` |
| Scalar program build | `make -C tests/cluster all`; `bash tools/build_riscv_tests.sh` | exit 0; 111 p-environment riscv-tests built (3 Zacas tests unavailable in installed assembler); two FP selections compiled separately |
| Integration smoke and scalar regression | From `snapshot-01/src/design`: `sbt "testOnly flow.cluster.ZveSystemSmokeSpec"` | exit 0; vector smoke tohost=1 at cycle 6855; 5 cluster programs and 13 selected riscv-tests passed. See `zve-smoke-isa-regression-final.log`. The suite now requires all selected ELFs. |
| Zve unit regression | `sbt "testOnly flow.rvv.zve.*"` | exit 0; 14/14 tests passed across 11 suites. See `zve-all-unit.log`. |
| Frozen E1 | Regenerate `single` and `single-rvv` from baseline and candidate, then `python3 records/zve32f-20261013/compare_e1.py ...` | pass; 85 `single` and 118 `single-rvv` generated files match after only source root and Scala assertion-line normalization. See `e1-result.json`. |
| New RTL | `sbt "runMain flow.top.GenerateBreezeCluster single-zve32f gshare linux coherent-dma"` | exit 0; marker includes AXI64, `zve32f`, VLEN 512, 8 vector L2 IDs. |
| KCU105 preparation | `python3 fpga/kcu105/target.py --cpu-type breeze-zve32f --with-sdcard --sys-clk-freq 100000000 --vivado-threads 4 --output-dir /home/chen/FUN/flow-runs/zve32f-20261013/kcu105-01` | exit 0; BIOS, CSR map and immutable copied SV source manifest generated. |
| KCU105 Vivado | In `kcu105-01/gateware`: `bash build_xilinx_kcu105.sh` | exit 1; synthesis completed, placement DRC UTLZ-1 failed before placer ran. No routed timing or bitstream. See `vivado.log`, `vivado-exit-code.txt`, and synthesis reports. |
| Linux/SD preparation | New Buildroot profile and DTS, `dtc` compile, `prepare_sd_boot.py --profile single-zve32f` with existing BE2 kernel Image/OpenSBI | DTS compilation and FAT-root packaging passed. These reuse existing software images and provide no Linux boot proof for Zve32f RTL. No SD card was written. |

The synthesis utilization report shows 280,628 / 242,400 CLB LUTs (115.77%). After `opt_design`, Vivado DRC still required 278,321 / 242,400 Slice LUTs and 259,609 / 242,400 LUTs as logic. `place_design` did not run. Synthesis WNS was -1.243 ns; this is **not** routed timing. Hierarchical synthesis assigns 205,660 LUTs to `ZveMount`, including 52,701 in VRF access, 22,661 in conversion, 11,570 in FMA, 21,696 in the element engine, and 23,488 in the slow unit. This exceeds the frozen KU040 capacity by 35,921 Slice LUTs even after optimization; a larger device or an approved architecture/resource change is needed to pursue a bitstream.

## Corrections made while validating

1. `ZveStreamEngine`: when a store request handshakes on the same cycle as the next VRF row return, retain the newly built request. The earlier ordering dropped it and the smoke watchdog timed out.
2. `ZveStreamEngine`: retain a load ID while an L2 request is stalled. The earlier combinational ID selection changed an unaccepted request when a different response freed an ID; L2's stable-REQ assertion caught it.
3. `ZveMount`: launch a Serial instruction only after its verdict is registered in the queue. The flow-through verdict could launch Serial one cycle before `ZveWindow.judged`, tripping `commit before oldest verdict`.
4. `ZveSystemSmokeSpec`: execute the smoke plus the mandatory selected scalar programs in one elaboration, and fail if a selected ELF is missing.

These changes retain the frozen stream credit count, lane counts, cache/TLB/L2/AXI configuration, and scalar profile circuits. The selected tests do not cover the full Zve32f ISA, Sv39 vector exception matrix, random L2 ordering, or Linux execution. No FPGA board test was possible after placement failed.

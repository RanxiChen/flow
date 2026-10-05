# R01 / W1 software workflow

Authority: [`docs/tasks/R01-rvv-workload-perfmodel.md`](../docs/tasks/R01-rvv-workload-perfmodel.md).
All builds and guest execution run on Alan in an independent checkout. No RTL
or existing Breeze directories are changed. W2 requires user confirmation.

## Fixed inputs and execution

`third_party/inputs.json` pins llama.cpp and the **official Qwen** GGUF revision.
`third_party/acquire.py` downloads these into an external run directory; it does not
copy upstream source or models into this repository. `prompt.txt` is UTF-8;
the runner removes its final newline before tokenization. It reports the real
prompt token count and generates exactly 16 token IDs using a greedy sampler.

The new runner calls upstream public APIs without changing upstream source or
kernels. Its three ELF-visible phase functions delimit prefill, decode and end.
Prefill includes the batched prompt evaluation and first sample. Decode includes
15 single-token evaluations and their samples. Decode totals are divided by 15,
not by 16. Initialization, loading, tokenization and cleanup are excluded.
Phase functions themselves are excluded from instruction counts.

Both context thread counts are 1. All simulations are serial and use
`nice -n 10`. Primary data uses Alan's GCC 15.1.0; Clang 18.1.3 is used only
for an intrinsics smoke comparison. RVV uses `-march=rv64gcv -mabi=lp64d`,
scalar uses `-march=rv64gc -mabi=lp64d` and disables V on the emulated CPU.
Upstream weight repacking retains its default setting. Optional Zfh/Zvfh,
Zicbop and Zihintpause are disabled to use the stated ISA baseline. BLAS and
OpenMP are disabled, and both RISC-V binaries link statically. The native
build also disables BLAS/OpenMP and uses the same upstream commit and models.

## Commands on Alan

Run from the independent checkout `/home/chen/FUN/flow-rvv-r01` on
`feat/rvv-20261005`. The run directory is outside the checkout. Use a working
HTTP proxy environment for downloads when direct network access fails.
The proxy is an operational dependency, not a build or measurement input.

```bash
git pull --ff-only
export R01_RUN=/home/chen/FUN/flow-r01-runs/20261005-w1
python3 rvv/third_party/acquire.py "$R01_RUN"
bash rvv/tools/probe.sh "$R01_RUN"
bash rvv/tools/build.sh "$R01_RUN"
bash rvv/tools/build-profile.sh "$R01_RUN"
python3 rvv/tools/tensors.py "$R01_RUN"
python3 rvv/tools/check_tokens.py "$R01_RUN"
python3 rvv/tools/run-profile.py "$R01_RUN"
python3 rvv/tools/summarize.py "$R01_RUN" "$R01_RUN/summary"
```

Save stdout/stderr for every command; `build.sh`, `probe.sh` and
`build-profile.sh` emit their actual commands with shell tracing. Python
execution scripts emit commands as JSON arrays. An individual quantization
can be checked with `check_tokens.py "$R01_RUN" q4_0` (or `q8_0`);
successful comparisons are retained. A mismatch stops the script and prevents
later profiling. A failed existing comparison also prevents automatic retries.

`build-profile.sh` verifies the plugin against a short sequence whose two
phases each execute three vector instructions and read/write exactly 16 bytes
at SEW=8, LMUL=1, VL=16. This is a plugin check, not model correctness evidence.

## Statistics conventions

The plugin uses ELF `STT_FUNC` ranges extracted with `readelf --syms`. Mangled
names are retained. Aliases at the same address are represented once;
instructions outside all recorded ranges are assigned to `[unknown]`.
Instruction counts are exclusive to each symbol, not inclusive call-tree
costs. Scalar/vector/configuration counts use QEMU inline scoreboards.
Configuration instructions are also included in the vector total.
Reads/writes of RVV CSRs (including `vlenb`) remain scalar instructions in
these totals and also appear in the opcode inventory with their CSR names.

For data instructions, VL and vtype are read at execution time using QEMU's
register API. `lmul_log2` records the signed LMUL exponent (0 means m1, -1
means mf2). Configuration instructions use SEW=VL=0 to indicate that they
do not process elements; their old vtype is not labeled as a data operation.
Memory bytes come from actual QEMU memory callbacks, including masked-access
behavior. Whole-register transfers count as unit stride; segmented transfers
have their own category. These are guest architectural access bytes, not DDR
traffic or cache-miss bytes.

For Q4_0/Q8_0 dot functions, entry argument `n` counts `n/32` weight blocks.
Repacked `ggml_gemv_*` functions additionally use argument `nc` to count all
output columns. Counts for a wrapper and its called helper remain separate;
do not add their block denominators. Per-block counts include the function's
own setup/epilogue, amortized over its executed blocks. Shared helper costs
are not assigned to their callers by the symbol-only profiler.

`summarize.py` writes deterministic small CSVs for totals, top 20 functions,
kernel instruction/block ratios, VL distributions, opcodes, nonmatrix symbol
groups, memory bytes, the deduplicated instruction list and scalar/RVV ratios.
Nonmatrix tables explicitly contain exclusive symbol costs; shared helper
costs are an attribution limit, not zero-cost operations.

To regenerate all summary tables in one command and compare two independent
regenerations:

```bash
python3 rvv/tools/summarize.py "$R01_RUN" "$R01_RUN/reproduce-a"
python3 rvv/tools/summarize.py "$R01_RUN" "$R01_RUN/reproduce-b"
diff -r "$R01_RUN/reproduce-a" "$R01_RUN/reproduce-b"
```

Only small summary CSVs may be committed. Models, binaries, raw profiles and
logs remain in the external Alan run directory.

Generate the Markdown workload report and all its dynamic statistics tables
from the same raw profiles in one command:

```bash
python3 rvv/tools/write-profile.py "$R01_RUN" "$R01_RUN/presentation"
```

The presentation directory contains `rvv-workload-profile.md` and the small
summary CSVs. Tensor inventories are generated separately by `tensors.py`.

To rerun a measured case independently and compare all recorded fields:

```bash
python3 rvv/tools/run-profile.py "$R01_RUN" q4_0-rvv-vlen128 repeat
python3 rvv/tools/compare-repeat.py "$R01_RUN" q4_0-rvv-vlen128
```

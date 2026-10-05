#!/usr/bin/env python3
"""Deterministic W1 CSV summaries. No timing/performance estimates."""
import collections
import argparse
import csv
from pathlib import Path
import sys
import json

parser = argparse.ArgumentParser()
parser.add_argument("run")
parser.add_argument("out")
parser.add_argument("--partial", action="store_true", help="Summarize only completed, token-matching cases")
options = parser.parse_args()
run, out = Path(options.run).resolve(), Path(options.out).resolve()
out.mkdir(parents=True, exist_ok=True)

def write(name, columns, rows):
    path = out / name
    with path.open("w") as f:
        writer = csv.writer(f)
        writer.writerow(columns)
        writer.writerows(rows)
    if path.stat().st_size >= 1_000_000:
        raise RuntimeError(f"Summary exceeds task limit: {path}")

totals, top, kernels, opcodes, histograms, nonmatrix, memories = [], [], [], [], [], [], []
base = ["quant", "mode", "vlen", "phase"]
groups = {
    "RMSNorm": ("rms_norm",), "RoPE": ("rope",),
    "softmax/attention": ("soft_max", "flash_attn"), "SiLU/Swiglu": ("silu", "swiglu"),
    "activation_quantization": ("quantize_row_q8_0", "quantize_mat_q8_0"),
    "sampling/argmax": ("sampler", "argmax",),
    "embedding_lookup": ("get_rows",),
}
inventory = set()
for quant in ("q4_0", "q8_0"):
    for mode, vlen in [("scalar", 128)] + [("rvv", v) for v in (128, 256, 512, 1024)]:
        stem = run / "profile" / f"{quant}-{mode}-vlen{vlen}"
        if options.partial:
            exit_path = Path(str(stem) + ".exit")
            if not exit_path.exists() or exit_path.read_text().strip() != "0":
                print("PARTIAL_SKIP", stem.name)
                continue
            actual = json.loads(Path(str(stem) + ".json").read_text())
            reference = json.loads((run / f"correctness/{quant}-native.json").read_text())
            if actual != reference:
                raise RuntimeError(f"Token mismatch in completed case: {stem.name}")
        funcs = list(csv.DictReader(Path(str(stem) + "-functions.csv").open()))
        vecs = list(csv.DictReader(Path(str(stem) + "-vectors.csv").open()))
        for phase in ("prefill", "decode"):
            key = [quant, mode, vlen, phase]
            rows = [r for r in funcs if r["phase"] == phase]
            total = sum(int(r["scalar"]) + int(r["vector"]) for r in rows)
            scalar = sum(int(r["scalar"]) for r in rows)
            vector = sum(int(r["vector"]) for r in rows)
            config = sum(int(r["vset"]) for r in rows)
            # Decode has 15 evaluations after the first token's prefill sample.
            tokens = 15 if phase == "decode" else None
            if phase == "prefill":
                tokens = json.loads((run / f"correctness/{quant}-native.json").read_text())["prompt_tokens"]
            totals.append(key + [tokens, total, scalar, vector, config, total / tokens])
            ranked = sorted(rows, key=lambda r: (-(int(r["scalar"]) + int(r["vector"])), r["symbol"]))
            for rank, r in enumerate(ranked[:20], 1):
                count = int(r["scalar"]) + int(r["vector"])
                top.append(key + [rank, r["symbol"], count, count / total, int(r["vector"])])
            for r in rows:
                if not ("ggml_vec_dot_q4_0_q8_0" in r["symbol"] or "ggml_vec_dot_q8_0_q8_0" in r["symbol"] or
                        "ggml_gemv_q4_0_" in r["symbol"] or "ggml_gemv_q8_0_" in r["symbol"]):
                    continue
                blocks = int(r["blocks"])
                kernels.append(key + [r["symbol"], int(r["calls"]), blocks, int(r["scalar"]), int(r["vector"]), int(r["vset"])] +
                               [int(r[column]) / blocks if blocks else "unavailable" for column in ("scalar", "vector", "vset")])
            for group, patterns in groups.items():
                selected = [r for r in rows if any(p in r["symbol"] for p in patterns)]
                count = sum(int(r["scalar"]) + int(r["vector"]) for r in selected)
                vectors = sum(int(r["vector"]) for r in selected)
                nonmatrix.append(key + [group, count, count / total, vectors, ";".join(sorted(r["symbol"] for r in selected))])
            for kind in ("load", "store"):
                for category in ("unit", "strided", "indexed", "segmented"):
                    count = sum(int(r[f"{kind}_{category}"]) for r in rows)
                    memories.append(key + [kind, category, count, count / tokens])
            counts = collections.Counter()
            for r in vecs:
                if r["phase"] != phase:
                    continue
                counts[r["opcode"]] += int(r["count"])
                inventory.add(r["opcode"])
                if any(p in r["symbol"] for p in ("ggml_vec_dot_q4_0_q8_0", "ggml_vec_dot_q8_0_q8_0", "ggml_gemv_q4_0_", "ggml_gemv_q8_0_")):
                    histograms.append(key + [r["symbol"], r["opcode"], int(r["sew"]), int(r["lmul_log2"]), int(r["vl"]), int(r["count"])])
            assert sum(n for op, n in counts.items() if not op.startswith("csr")) == vector, (key, counts, vector)
            for op, count in sorted(counts.items(), key=lambda x: (-x[1], x[0])):
                if op.startswith("csr"):
                    category = "rvv_csr_scalar_instruction"
                elif op.startswith("vset"):
                    category = "configuration"
                elif op.startswith(("vl", "vs")) and op.endswith(".v"):
                    category = "memory"
                elif "red" in op:
                    category = "reduction"
                elif op.startswith(("vwmul", "vwmacc")):
                    category = "widening_multiply_accumulate"
                elif op.startswith(("vsll", "vsrl", "vsra", "vand", "vor", "vxor", "vns", "vnclip")):
                    category = "shift_unpack_bitwise"
                else:
                    category = "other"
                opcodes.append(key + [op, category, count, count / sum(counts.values()) if counts else 0])

write("totals.csv", base + ["tokens", "total", "scalar", "vector", "vset", "instructions_per_token"], totals)
write("top20.csv", base + ["rank", "symbol", "instructions", "fraction", "vector"], top)
write("kernels.csv", base + ["symbol", "calls", "blocks32", "scalar", "vector", "vset", "scalar_per_block", "vector_per_block", "vset_per_block"],
      sorted(kernels))
write("kernel-vl.csv", base + ["symbol", "opcode", "sew", "lmul_log2", "vl", "count"], sorted(histograms))
write("opcodes.csv", base + ["opcode", "category", "count", "fraction_of_vector_and_rvv_csr"], opcodes)
write("nonmatrix.csv", base + ["group", "exclusive_symbol_instructions", "fraction", "vector", "symbols"], nonmatrix)
write("memory.csv", base + ["kind", "category", "bytes", "bytes_per_token"], memories)
write("instruction-list.csv", ["opcode"], [[op] for op in sorted(inventory)])
ratios = []
for r in totals:
    if r[1] != "rvv":
        continue
    control = next(s for s in totals if s[0] == r[0] and s[1] == "scalar" and s[3] == r[3])
    ratios.append(r[:4] + [control[5], r[5], control[5] / r[5]])
write("scalar-ratio.csv", base + ["scalar_instructions", "rvv_instructions", "scalar_over_rvv"], ratios)
print("W1_PARTIAL_SUMMARIES" if options.partial else "W1_SUMMARIES_COMPLETE", out)

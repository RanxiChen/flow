#!/usr/bin/env python3
"""Use the pinned upstream GGUF reader; output small tensor inventory CSVs."""
import collections
import csv
import json
from pathlib import Path
import sys

run = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(run / "third_party/llama.cpp/gguf-py"))
from gguf import GGUFReader

out = run / "summary"
out.mkdir(exist_ok=True)
summary = {}
for quant in ("q4_0", "q8_0"):
    reader = GGUFReader(run / f"models/qwen2.5-0.5b-instruct-{quant}.gguf")
    types = collections.Counter()
    with (out / f"tensors-{quant}.csv").open("w") as f:
        writer = csv.writer(f)
        writer.writerow(["name", "gguf_dimensions", "type", "elements", "bytes"])
        for tensor in reader.tensors:
            writer.writerow([tensor.name, "x".join(map(str, tensor.shape)), tensor.tensor_type.name,
                             tensor.n_elements, tensor.n_bytes])
            types[tensor.tensor_type.name] += 1
    summary[quant] = {"tensor_count": len(reader.tensors), "types": dict(types),
                      "tensor_bytes": sum(t.n_bytes for t in reader.tensors),
                      "token_embd": [{"name": t.name, "type": t.tensor_type.name,
                                      "dimensions": list(map(int, t.shape)), "bytes": t.n_bytes}
                                     for t in reader.tensors if t.name in ("token_embd.weight", "output.weight")]}
(out / "tensors.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))

#!/usr/bin/env python3
"""Fetch fixed upstream sources and official models outside the Flow checkout."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
inputs = json.loads((ROOT / "third_party/inputs.json").read_text())
run = Path(sys.argv[1]).resolve()
run.mkdir(parents=True, exist_ok=True)

def call(args):
    print("COMMAND", json.dumps([str(a) for a in args]), flush=True)
    subprocess.run(args, check=True)

source = run / "third_party/llama.cpp"
if not (source / ".git").exists():
    source.parent.mkdir(exist_ok=True)
    call(["git", "clone", "--depth", "1", inputs["llama_url"], source])
head = subprocess.check_output(["git", "-C", source, "rev-parse", "HEAD"], text=True).strip()
if head != inputs["llama_commit"]:
    call(["git", "-C", source, "fetch", "--depth", "1", "origin", inputs["llama_commit"]])
    call(["git", "-C", source, "checkout", "--detach", inputs["llama_commit"]])
call(["git", "-C", source, "log", "-1", "--format=%H %cI"])
models = run / "models"
models.mkdir(exist_ok=True)
manifest = []
for name in inputs["model_files"]:
    path = models / name
    if not path.exists():
        url = f'https://huggingface.co/{inputs["model_repo"]}/resolve/{inputs["model_revision"]}/{name}'
        call(["curl", "-fL", "--connect-timeout", "30", "--max-time", "1800", "--retry", "3", url, "-o", str(path) + ".partial"])
        os.rename(str(path) + ".partial", path)
    digest = hashlib.sha256()
    with path.open("rb") as f:
        if f.read(4) != b"GGUF":
            raise RuntimeError(f"Not GGUF: {path}")
        f.seek(0)
        while data := f.read(1024 * 1024):
            digest.update(data)
    manifest.append({"file": name, "size": path.stat().st_size, "sha256": digest.hexdigest()})
(run / "models.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest, indent=2))

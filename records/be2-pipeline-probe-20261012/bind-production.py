"""Bind a fresh prepared SoC to source, immutable RTL, and the actual BIOS ROM."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument("--repo", type=Path, required=True)
p.add_argument("--hardware", type=Path, required=True)
p.add_argument("--source", required=True)
a = p.parse_args()

def git(*args):
    return subprocess.check_output(["git", "-C", str(a.repo), *args], text=True).strip()

assert git("rev-parse", "HEAD") == a.source
assert not git("status", "--porcelain", "--untracked-files=no")
assert (a.hardware / "source-commit.txt").read_text().strip() == a.source
manifest = json.loads((a.hardware / "source-snapshot/manifest.json").read_text())
names = set()
for entry in manifest:
    source = Path(entry["snapshot"])
    assert hashlib.sha256(source.read_bytes()).hexdigest() == entry["sha256"]
    names.add(source.name)
assert {"BreezeFrontendPipeline.sv", "BreezeBackend.sv", "L1DCache.sv"} <= names
assert not names.intersection({"BreezeFrontend.sv", "BreezeFrontendBoundary.sv",
                               "BreezeFetchTranslator.sv", "BreezeInstrRealigner.sv",
                               "FetchTlbClient.sv", "L1ICache.sv", "FetchBuffer.sv"})
cfg = dict(line.split("=", 1) for line in
           (a.hardware / "cluster-profile.txt").read_text().splitlines() if "=" in line)
assert cfg["coherentDma"] == "true" and cfg["privilege"] == "linux"
words = (a.hardware / "gateware/xilinx_kcu105_rom.init").read_text().splitlines()
rom = b"".join(int(w, 16).to_bytes(8, "little") for w in words)
bios = (a.hardware / "software/bios/bios.bin").read_bytes()
assert bios and rom[:len(bios)] == bios
binding = dict(source_sha=a.source, source_dirty=False, host="Alan",
               hostname=subprocess.check_output(["hostname"], text=True).strip(),
               cwd=str(a.repo), profile=(a.hardware / "product-profile.txt").read_text().strip(),
               clock_hz=100000000, target="xcku040-ffva1156-2-e", vivado="2022.2",
               frontend="BreezeFrontendPipeline", backend="BE2-P BreezeBackend",
               debug=cfg["debug"] == "true", tandem=cfg["tandem"] == "true",
               golden_changed=False, board="NOT_RUN", new_timing_exceptions=False,
               bios_sha256=hashlib.sha256(bios).hexdigest(), bios_rom_matches=True)
(a.hardware / "build-binding.json").write_text(json.dumps(binding, indent=2) + "\n")
print(json.dumps(binding, indent=2))

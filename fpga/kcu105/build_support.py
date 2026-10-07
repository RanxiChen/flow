"""Freeze the exact RTL inputs consumed by Vivado."""
from pathlib import Path
import shutil
import hashlib
import json
import re

from litex.soc.integration.builder import Builder


class SnapshotBuilder(Builder):
    def build(self, *args, **kwargs):
        self.freeze_sources()
        self.check_l2_sources()
        # Execute the mapping gate immediately after synthesis reports/checkpoint,
        # before opt_design or place_design. Copy the hook into this build's snapshot.
        hook = Path(self.output_dir).resolve() / "source-snapshot" / "soc2-bram-gate.tcl"
        shutil.copy2(Path(__file__).with_name("soc2-bram-gate.tcl"), hook)
        self.soc.platform.toolchain.pre_synthesis_commands.append(
            'set_msg_config -id {{Synth 8-4767}} -limit 100000')
        self.soc.platform.toolchain.pre_optimize_commands.add(f'source {hook}')
        return super().build(*args, **kwargs)

    def check_l2_sources(self):
        l2_sources = [Path(s[0]) for s in self.soc.platform.sources
                      if Path(s[0]).name == "L2Home.sv"]
        if not l2_sources:
            raise RuntimeError("D5: generated L2Home.sv is missing")
        for source in l2_sources:
            text = source.read_text()
            for name in ("data", "meta", "plruArr"):
                if not re.search(r'\bSdpSram(?:_\d+)?\s+' + name + r'\s*\(', text):
                    raise RuntimeError(f"D5: {source}: {name} is not a SdpSram instance")

    def freeze_sources(self):
        # Vivado consumes immutable copies, not a later sbt elaboration or
        # changing external CVFPU checkout. Keep original names for includes.
        snapshot = Path(self.output_dir) / "source-snapshot"
        sources = []
        manifest = []
        for index, source in enumerate(self.soc.platform.sources):
            original = Path(source[0]).resolve()
            dest = snapshot / f"source-{index}" / original.name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(original, dest)
            sources.append((str(dest), *source[1:]))
            manifest.append({"original": str(original), "snapshot": str(dest),
                             "sha256": hashlib.sha256(dest.read_bytes()).hexdigest()})
        self.soc.platform.sources = sources
        includes = []
        for index, original in enumerate(self.soc.platform.verilog_include_paths):
            dest = snapshot / f"include-{index}"
            shutil.copytree(original, dest, dirs_exist_ok=True)
            includes.append(str(dest))
        self.soc.platform.verilog_include_paths = includes
        (snapshot / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

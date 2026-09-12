"""Freeze gateware inputs without replacing official LiteX software."""
from pathlib import Path
import shutil
import hashlib
import json

from litex.soc.integration.builder import Builder


class SnapshotBuilder(Builder):
    def build(self, *args, **kwargs):
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
        return super().build(*args, **kwargs)

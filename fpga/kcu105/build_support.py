"""Freeze gateware inputs and apply the reviewed official SD driver patch."""
from pathlib import Path
import shutil
import hashlib
import json
import subprocess

from litex.soc.integration.builder import Builder


class SnapshotBuilder(Builder):
    def build(self, *args, **kwargs):
        gateware = Path(self.gateware_dir)
        gateware.mkdir(parents=True, exist_ok=True)
        shutil.copy2(Path(__file__).with_name("sd_timing.tcl"), gateware / "flow_sd_timing.tcl")
        self.soc.platform.toolchain.pre_optimize_commands.append("source flow_sd_timing.tcl")
        self.soc.platform.toolchain.additional_commands.append("flow_report_sd_timing")
        # Patch a build-local official package; never dirty the installed LiteX.
        patch_dir = Path(__file__).resolve().parent / "patches"
        lock = json.loads((patch_dir / "litex-sdcard.json").read_text())
        packages = []
        for name, source in self.software_packages:
            if name == "liblitesdcard":
                source = Path(source)
                digest = hashlib.sha256((source / "sdcard.c").read_bytes()).hexdigest()
                if digest != lock["sdcard_sha256"]:
                    raise RuntimeError("LiteX SD driver changed: review the Flow patch before building")
                dest = Path(self.output_dir) / "software-source" / name
                shutil.copytree(source, dest, dirs_exist_ok=True)
                subprocess.run(["patch", "--batch", "--fuzz=0", "-p1", "-i",
                                str(patch_dir / "litex-sdcard.patch")], cwd=dest, check=True)
                source = str(dest)
                (dest.parent / "sdcard-upstream.json").write_text(json.dumps(lock, indent=2) + "\n")
            packages.append((name, source))
        self.software_packages = packages
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

"""KCU105 SD integration; vendor checkouts remain unchanged."""
from pathlib import Path
import shutil
import hashlib
import json

from migen import Cat, Constant, If, ResetInserter, Signal
from migen.genlib.cdc import MultiReg
from litex.gen import LiteXModule
from litex.build.generic_platform import Pins, IOStandard, Misc
from litex.soc.interconnect.csr import CSR, CSRStatus
from litex.soc.integration.builder import Builder, soc_directory
from flow.core import BreezeDma, BreezeTinyDma, BreezeTinyDebugDma


class SdDmaStatus(LiteXModule):
    def __init__(self, cpu, sdcard, cd):
        self.reset_request = CSR(name="reset")
        self.error = CSRStatus(name="error")
        self.error_address = CSRStatus(32, name="error_address")
        self.present = CSRStatus(name="present")
        fault = Signal()
        not_present = Signal(reset=1)
        self.specials += MultiReg(cd, not_present)
        self.comb += [self.error.status.eq(fault), self.present.status.eq(~not_present)]
        bus = cpu.dma_bus
        self.sync += If(self.reset_request.re,
            fault.eq(0), self.error_address.status.eq(0),
        ).Elif(bus.cyc & bus.stb & bus.err,
            fault.eq(1), self.error_address.status.eq(Cat(Constant(0, 3), bus.adr)),
        )
        # Stock LiteX DMA only consumes ACK. Stop further requests after ERR;
        # firmware reads the sticky error and resets DMA/PHY instead of faking ACK.
        cpu.cpu_params["i_io_dmaWishbone_cyc"] = bus.cyc & ~fault
        cpu.cpu_params["i_io_dmaWishbone_stb"] = bus.stb & ~fault
        ResetInserter()(sdcard)
        self.comb += sdcard.reset.eq(self.reset_request.re)


def add_sdcard(soc):
    soc.add_sdcard(mode="read+write")
    soc.platform.add_extension([
        ("sdcard_cd", 0, Pins("AM10"), IOStandard("LVCMOS18"), Misc("PULLUP=TRUE")),
    ])
    soc.sd_dma = SdDmaStatus(soc.cpu, soc.sdcard, soc.platform.request("sdcard_cd"))
    soc.add_constant("CONFIG_BIOS_NO_BOOT", 1)  # Manual sdcardboot after read/CRC checks.
    pads = soc.platform.lookup_request("sdcard")
    # The PHY uses sys-clocked I/O registers and a software divider/clock gate.
    # Bound internal pad routing; these are NOT a full card/mux timing model.
    # Do not hide the port paths with blanket false paths or claim SD STA closure.
    for pin in (pads.cmd, pads.data):
        port = "{{{p}[*]}}" if len(pin) > 1 else "{{{p}}}"
        soc.platform.add_platform_command(
            "set_max_delay -datapath_only 5.000 -from [get_ports " + port + "]", p=pin)
        soc.platform.add_platform_command(
            "set_max_delay -datapath_only 5.000 -to [get_ports " + port + "]", p=pin)
    bus = soc.cpu.dma_bus
    probes = [("dma_address", Cat(Constant(0, 3), bus.adr))]
    probes += [("dma_" + n, getattr(bus, n)) for n in ("cyc", "stb", "ack", "err", "we", "sel")]
    probes += [("sd_cmd", pads.cmd), ("sd_data", pads.data),
               ("sd_clock", pads.clk), ("sd_present", soc.sd_dma.present.status),
               ("sd_dma_error", soc.sd_dma.error.status),
               ("sd_cmd_event", soc.sdcard.core.cmd_event.status),
               ("sd_data_event", soc.sdcard.core.data_event.status)]
    return probes


class SdBuilder(Builder):
    """Per-build firmware overlay, preserving the installed LiteX sources."""
    def __init__(self, *args, **kwargs):
        output = Path(kwargs["output_dir"]).resolve()
        overlay = output / "software-source"
        source = Path(__file__).parent / "software"
        self.flow_packages = {}
        for name in ("bios", "liblitesdcard"):
            dest = overlay / name
            shutil.copytree(Path(soc_directory) / "software" / name, dest, dirs_exist_ok=True)
            self.flow_packages[name] = str(dest)
        for name in ("sdcard.c", "sdcard_flow.h"):
            shutil.copy2(source / name, overlay / "liblitesdcard" / name)
        shutil.copy2(source / "cmd_sdcard.c", overlay / "bios" / "cmds" / "cmd_litesdcard.c")
        makefile = overlay / "bios" / "Makefile"
        makefile.write_text(makefile.read_text() + "\nCFLAGS += -I$(BIOS_DIRECTORY)/..\n")
        super().__init__(*args, **kwargs)

    def add_software_package(self, name, src_dir=None):
        super().add_software_package(name, self.flow_packages.get(name, src_dir))

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

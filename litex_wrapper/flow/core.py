"""Production LiteX CPU wrappers for Breeze Linux clusters.

``Breeze`` exposes four harts; ``BreezeTiny`` exposes one hart with L2/Home.
Both default to RV64GC, the gshare preset, Linux privilege support and production
(no Tandem) RTL. The target-internal ``BreezeTinyDebug`` enables FPGA traces.
Legacy configurable
simulation wrappers live in :mod:`flow.cluster` and reuse the private base
class below; they are not part of the FPGA product interface.
"""

import os

from migen import Cat, ClockSignal, Constant, Instance, Record, ResetSignal, Signal

from litex.soc.cores.cpu import CPU, CPU_GCC_TRIPLE_RISCV64
from litex.soc.interconnect import wishbone


RETIRE_LAYOUT = [
    ("valid", 1),
    ("pc", 64),
    ("inst", 32),
    ("next_pc", 64),
    ("estop", 1),
    ("rd_write_en", 1),
    ("rd_addr", 5),
    ("rd_data", 64),
    ("mem_en", 1),
    ("mem_is_write", 1),
    ("mem_addr", 64),
    ("mem_aligned_addr", 64),
    ("mem_rdata", 64),
    ("mem_wdata", 64),
    ("mem_wmask", 8),
]

RETIRE_RTL_NAMES = {
    "valid": "valid",
    "pc": "pc",
    "inst": "inst",
    "next_pc": "nextPc",
    "estop": "estop",
    "rd_write_en": "rdWriteEn",
    "rd_addr": "rdAddr",
    "rd_data": "rdData",
    "mem_en": "memEn",
    "mem_is_write": "memIsWrite",
    "mem_addr": "memAddr",
    "mem_aligned_addr": "memAlignedAddr",
    "mem_rdata": "memRData",
    "mem_wdata": "memWData",
    "mem_wmask": "memWMask",
}

DCACHE_TRACE_LAYOUT = [
    ("request_valid", 1),
    ("response_valid", 1),
    ("address", 64),
    ("size_log2", 3),
    ("is_write", 1),
    ("write_data", 64),
    ("mask", 8),
    ("pma_allowed", 1),
    ("pma_cacheable", 1),
    ("pma_device", 1),
    ("cache_hit", 1),
    ("response_data", 64),
    ("response_error", 1),
]

DCACHE_TRACE_RTL_NAMES = {
    "request_valid": "requestValid",
    "response_valid": "responseValid",
    "address": "address",
    "size_log2": "sizeLog2",
    "is_write": "isWrite",
    "write_data": "writeData",
    "mask": "mask",
    "pma_allowed": "pmaAllowed",
    "pma_cacheable": "pmaCacheable",
    "pma_device": "pmaDevice",
    "cache_hit": "cacheHit",
    "response_data": "responseData",
    "response_error": "responseError",
}


class _BreezeClusterCPU(CPU):
    """Shared wiring implementation; concrete wrappers freeze the profile."""

    cluster_profile = None
    num_harts = None
    l2_bytes = None
    core_preset = None
    privilege_profile = None
    rtl_mode = None
    tandem_enabled = None
    gcc_arch = None
    gcc_abi = None
    gcc_defines = "-D__breeze__"
    reset_vector = None
    expected_marker = {}
    coherent_dma = False

    category = "softcore"
    family = "riscv"
    data_width = 64
    endianness = "little"
    gcc_triple = CPU_GCC_TRIPLE_RISCV64
    linker_output_format = "elf64-littleriscv"
    nop = "nop"
    io_regions = {
        0x0200_0000: 0x0001_0000,  # CLINT.
        0x0c00_0000: 0x0400_0000,  # PLIC.
        0x1200_0000: 0x0100_0000,  # LiteX MMIO window.
    }

    @property
    def gcc_flags(self):
        return (
            f"-march={self.gcc_arch} -mabi={self.gcc_abi} "
            "-mno-save-restore -mcmodel=medany "
            f"{self.gcc_defines}"
        )

    def __init__(self, platform, variant="standard"):
        if variant not in self.variants:
            raise ValueError(
                f"Unsupported Breeze variant {variant!r}; expected {self.variants}")
        if not all((self.cluster_profile, self.num_harts, self.core_preset,
                    self.privilege_profile, self.rtl_mode, self.gcc_arch,
                    self.gcc_abi, self.reset_vector)):
            raise RuntimeError("Breeze CPU profile is incomplete")

        self.platform = platform
        self.variant = variant
        self.reset = Signal()

        # Cached/coherent refills and writebacks leave through memory_bus;
        # uncached device accesses leave through mmio_bus. Both must reach the
        # LiteX decoder because the reset ROM is fetched through memory_bus.
        self.memory_bus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.mmio_bus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.periph_buses = [self.memory_bus, self.mmio_bus]
        self.memory_buses = []
        if self.coherent_dma:
            self.dma_bus = wishbone.Interface(
                data_width=64, address_width=32, addressing="word")

        # Non-canonical compatibility aliases for existing passive monitors.
        self.ibus = self.memory_bus
        self.dbus = self.mmio_bus

        self.interrupt = Signal(8)
        self.msip = Signal(self.num_harts)
        self.mtip = Signal(self.num_harts)
        self.time = Signal(64)
        self.meip = Signal(self.num_harts)
        self.seip = Signal(self.num_harts)
        self.hart_fatal = Signal(self.num_harts)
        self.hart_estop = Signal(self.num_harts)
        self.retires = [Record(RETIRE_LAYOUT) for _ in range(self.num_harts)]
        self.dcache_traces = [
            Record(DCACHE_TRACE_LAYOUT) for _ in range(self.num_harts)
        ]
        self.retire = self.retires[0]

        memory_bus = self.memory_bus
        mmio_bus = self.mmio_bus
        self.cpu_params = dict(
            i_clock=ClockSignal("sys"),
            i_reset=ResetSignal("sys") | self.reset,
            i_io_resetAddr=Constant(self.reset_vector, 64),
            i_io_time=self.time,

            o_io_memoryWishbone_adr=memory_bus.adr,
            o_io_memoryWishbone_dat_w=memory_bus.dat_w,
            i_io_memoryWishbone_dat_r=memory_bus.dat_r,
            o_io_memoryWishbone_sel=memory_bus.sel,
            o_io_memoryWishbone_cyc=memory_bus.cyc,
            o_io_memoryWishbone_stb=memory_bus.stb,
            i_io_memoryWishbone_ack=memory_bus.ack,
            o_io_memoryWishbone_we=memory_bus.we,
            o_io_memoryWishbone_cti=memory_bus.cti,
            o_io_memoryWishbone_bte=memory_bus.bte,
            i_io_memoryWishbone_err=memory_bus.err,

            o_io_mmioWishbone_adr=mmio_bus.adr,
            o_io_mmioWishbone_dat_w=mmio_bus.dat_w,
            i_io_mmioWishbone_dat_r=mmio_bus.dat_r,
            o_io_mmioWishbone_sel=mmio_bus.sel,
            o_io_mmioWishbone_cyc=mmio_bus.cyc,
            o_io_mmioWishbone_stb=mmio_bus.stb,
            i_io_mmioWishbone_ack=mmio_bus.ack,
            o_io_mmioWishbone_we=mmio_bus.we,
            o_io_mmioWishbone_cti=mmio_bus.cti,
            o_io_mmioWishbone_bte=mmio_bus.bte,
            i_io_mmioWishbone_err=mmio_bus.err,
        )

        for hart in range(self.num_harts):
            self.cpu_params[f"i_io_msip_{hart}"] = self.msip[hart]
            self.cpu_params[f"i_io_mtip_{hart}"] = self.mtip[hart]
            if self.privilege_profile == "linux":
                self.cpu_params[f"i_io_externalInterrupts_{hart}"] = Cat(
                    self.meip[hart], Constant(0, 7))
                self.cpu_params[
                    f"i_io_supervisorExternalInterrupts_{hart}"
                ] = self.seip[hart]
            else:
                self.cpu_params[f"i_io_externalInterrupts_{hart}"] = (
                    self.interrupt if hart == 0 else Constant(0, 8))
                self.cpu_params[
                    f"i_io_supervisorExternalInterrupts_{hart}"
                ] = Constant(0)
            self.cpu_params[f"o_io_hartFatal_{hart}"] = self.hart_fatal[hart]
            self.cpu_params[f"o_io_hartEStop_{hart}"] = self.hart_estop[hart]
            for field_name, _ in RETIRE_LAYOUT:
                rtl_name = RETIRE_RTL_NAMES[field_name]
                self.cpu_params[f"o_io_retire_{hart}_{rtl_name}"] = getattr(
                    self.retires[hart], field_name)
            for field_name, _ in DCACHE_TRACE_LAYOUT:
                rtl_name = DCACHE_TRACE_RTL_NAMES[field_name]
                self.cpu_params[f"o_io_dcacheTrace_{hart}_{rtl_name}"] = getattr(
                    self.dcache_traces[hart], field_name)

        if self.coherent_dma:
            for name in ("adr", "dat_w", "sel", "cyc", "stb", "we", "cti", "bte"):
                self.cpu_params["i_io_dmaWishbone_" + name] = getattr(self.dma_bus, name)
            for name in ("dat_r", "ack", "err"):
                self.cpu_params["o_io_dmaWishbone_" + name] = getattr(self.dma_bus, name)
        self.add_sources(platform)

    def set_reset_address(self, reset_address):
        if reset_address != self.reset_vector:
            raise ValueError(
                "Breeze reset address is fixed by the selected platform: "
                f"expected 0x{self.reset_vector:016x}, got 0x{reset_address:016x}")
        self.reset_address = reset_address
        self.cpu_params["i_io_resetAddr"] = Constant(reset_address, 64)

    @classmethod
    def flow_root_dir(cls):
        current_dir = os.path.dirname(os.path.abspath(__file__))
        return os.path.dirname(os.path.dirname(current_dir))

    @classmethod
    def rtl_dir(cls):
        base = os.path.join(
            cls.flow_root_dir(), "design", "build", "rtl", "cluster",
            cls.cluster_profile, cls.core_preset)
        if cls.privilege_profile != "mcu":
            base = os.path.join(base, cls.privilege_profile)
        return base

    @classmethod
    def _read_profile_marker(cls, marker_path):
        values = {}
        with open(marker_path, encoding="utf-8") as marker_file:
            for raw_line in marker_file:
                line = raw_line.strip()
                if not line or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip()
        return values

    @classmethod
    def _validate_profile_marker(cls, marker_path):
        values = cls._read_profile_marker(marker_path)
        expected = {
            "profile": cls.cluster_profile,
            "numHarts": str(cls.num_harts),
            "l1iBytes": "8192",
            "l1dBytes": "8192",
            "l2Bytes": str(cls.l2_bytes),
            "lineBytes": "32",
            "l1Ways": "4",
            "l2Ways": "8",
            "corePreset": cls.core_preset,
            "privilegeProfile": cls.privilege_profile,
            **cls.expected_marker,
        }
        mismatches = {
            key: (values.get(key), expected_value)
            for key, expected_value in expected.items()
            if values.get(key) != expected_value
        }
        if mismatches:
            detail = ", ".join(
                f"{key}: expected={expected_value} actual={actual}"
                for key, (actual, expected_value) in sorted(mismatches.items()))
            raise RuntimeError(f"Breeze cluster profile mismatch: {detail}")

    @classmethod
    def add_sources(cls, platform):
        rtl_dir = cls.rtl_dir()
        filelist = os.path.join(rtl_dir, "filelist.f")
        profile_marker = os.path.join(rtl_dir, "cluster-profile.txt")
        if not os.path.isfile(filelist) or not os.path.isfile(profile_marker):
            raise FileNotFoundError(
                "Breeze RTL has not been elaborated. Generate it with:\n"
                f"  cd {os.path.join(cls.flow_root_dir(), 'design')} && "
                "sbt \"runMain flow.top.GenerateBreezeMulticoreClusterWishbone "
                f"{cls.cluster_profile} {cls.core_preset} "
                f"{cls.privilege_profile} {cls.rtl_mode}\"")

        cls._validate_profile_marker(profile_marker)
        marker = cls._read_profile_marker(profile_marker)
        dma_marker = marker.get("coherentDma", "false")
        if dma_marker != str(cls.coherent_dma).lower():
            raise RuntimeError("Breeze coherent DMA RTL does not match the selected CPU")
        if cls.coherent_dma:
            import hashlib
            with open(os.path.join(cls.flow_root_dir(), "config", "breeze_mcu_platform.json"), "rb") as stream:
                current_hash = hashlib.sha256(stream.read()).hexdigest()
            if marker.get("platformSha256") != current_hash:
                raise RuntimeError("Breeze PMA configuration changed: regenerate coherent DMA RTL")

        with open(filelist, encoding="utf-8") as rtl_manifest:
            entries = [
                line.split("#", 1)[0].strip()
                for line in rtl_manifest
                if line.split("#", 1)[0].strip()
            ]
        if not entries:
            raise RuntimeError(f"Breeze cluster RTL manifest is empty: {filelist}")

        include_paths = [
            entry[len("+incdir+"):] for entry in entries
            if entry.startswith("+incdir+")
        ]
        source_entries = [
            entry for entry in entries if not entry.startswith("+incdir+")
        ]
        rtl_files = [
            entry if os.path.isabs(entry) else os.path.join(rtl_dir, entry)
            for entry in source_entries
        ]
        missing_files = [path for path in rtl_files if not os.path.isfile(path)]
        if missing_files:
            missing = "\n".join(f"  {path}" for path in missing_files)
            raise FileNotFoundError(
                "Breeze cluster RTL manifest references missing files:\n" + missing)

        for include_path in include_paths:
            if not os.path.isdir(include_path):
                raise FileNotFoundError(
                    f"Breeze cluster RTL include path is missing: {include_path}")
            platform.add_verilog_include_path(include_path)
        for rtl_file in rtl_files:
            platform.add_source(rtl_file)

    def do_finalize(self):
        if not hasattr(self, "reset_address"):
            raise RuntimeError("LiteX did not assign the Breeze reset address")
        self.specials += Instance(
            "BreezeMulticoreClusterWishbone", **self.cpu_params)


class Breeze(_BreezeClusterCPU):
    """Fixed four-hart RV64GC Linux CPU product for LiteX FPGA targets."""

    name = "breeze"
    human_name = "Breeze RV64GC Linux (4 harts)"
    variants = ("standard",)
    cluster_profile = "small"
    num_harts = 4
    l2_bytes = 65536
    core_preset = "gshare"
    privilege_profile = "linux"
    rtl_mode = "production"
    tandem_enabled = False
    gcc_arch = "rv64imafdc_zicsr_zifencei"
    gcc_abi = "lp64d"
    gcc_defines = "-D__breeze__ -D__riscv_plic__"
    reset_vector = 0x1001_0000
    mem_map = {
        "rom": reset_vector,
        "sram": 0x1100_0000,
        "csr": 0x1200_0000,
        "main_ram": 0x8000_0000,
    }
    expected_marker = {
        "rtlMode": "production",
        "tandem": "false",
        "compressed": "true",
        "addressTranslation": "bare,sv39",
    }


class BreezeTiny(Breeze):
    """Single-hart Linux product retaining the MESI L1D and L2/Home fabric."""

    name = "breeze-tiny"
    human_name = "Breeze Tiny RV64GC Linux (1 hart)"
    cluster_profile = "single"
    num_harts = 1
    l2_bytes = 16384


class BreezeTinyDebug(BreezeTiny):
    """Target-internal single-hart product with live FPGA trace outputs."""

    human_name = "Breeze Tiny RV64GC Linux (1 hart, ILA debug)"
    rtl_mode = "fpga-debug"
    tandem_enabled = True
    expected_marker = {
        **BreezeTiny.expected_marker,
        "rtlMode": "fpga-debug",
        "tandem": "true",
    }

    @classmethod
    def rtl_dir(cls):
        return os.path.join(super().rtl_dir(), "fpga-debug")


class CoherentDmaCPU:
    coherent_dma = True

    @classmethod
    def rtl_dir(cls):
        return os.path.join(super().rtl_dir(), "coherent-dma")


# LiteX derives CPU_DIRECTORY from the concrete class's source file. Keep
# these variants beside system.h/crt0.S, even when a board selects the DMA IP.
class BreezeDma(CoherentDmaCPU, Breeze):
    pass


class BreezeTinyDma(CoherentDmaCPU, BreezeTiny):
    pass


class BreezeTinyDebugDma(CoherentDmaCPU, BreezeTinyDebug):
    pass

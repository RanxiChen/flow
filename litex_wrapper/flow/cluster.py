"""LiteX CPU integration wrapper for the Breeze multicore cluster.

One cluster RTL instance is exposed to LiteX as a single CPU object (spec
section 21): a memory Wishbone master (L2/Home -> RAM), an MMIO Wishbone
master (per-hart arbiter output), per-hart msip/mtip/external-interrupt
inputs and per-hart fatal/estop/retire debug outputs. Generate the selected
cluster RTL profile separately before constructing a SoC.
"""

import os
from migen import Cat, ClockSignal, Constant, Instance, Record, ResetSignal, Signal

from litex.soc.interconnect import wishbone

from litex.soc.cores.cpu import CPU, CPU_GCC_TRIPLE_RISCV64

# Variants -----------------------------------------------------------------------------------------

CPU_VARIANTS = ["minimal"]

# GCC Flags ----------------------------------------------------------------------------------------

GCC_FLAGS = {
    "minimal": "-march=rv64i2p0       -mabi=lp64 "
}

# Frozen cluster profiles (spec section 3.3) --------------------------------------------------------

CLUSTER_PROFILES = ("single", "dual", "small")
CLUSTER_NUM_HARTS = {"single": 1, "dual": 2, "small": 4}
CLUSTER_L2_BYTES = {"single": 16384, "dual": 32768, "small": 65536}
CORE_PRESETS = ("baseline", "gshare")

RETIRE_LAYOUT = [
    ("valid",            1),
    ("pc",              64),
    ("inst",            32),
    ("next_pc",         64),
    ("estop",            1),
    ("rd_write_en",      1),
    ("rd_addr",          5),
    ("rd_data",         64),
    ("mem_en",           1),
    ("mem_is_write",     1),
    ("mem_addr",        64),
    ("mem_aligned_addr", 64),
    ("mem_rdata",       64),
    ("mem_wdata",       64),
    ("mem_wmask",        8),
]

# migen record field -> Verilog port segment (Chisel camelCase; acronym
# capitals like WMask/RData make an explicit table safer than a conversion).
RETIRE_RTL_NAMES = {
    "valid":            "valid",
    "pc":               "pc",
    "inst":             "inst",
    "next_pc":          "nextPc",
    "estop":            "estop",
    "rd_write_en":      "rdWriteEn",
    "rd_addr":          "rdAddr",
    "rd_data":          "rdData",
    "mem_en":           "memEn",
    "mem_is_write":     "memIsWrite",
    "mem_addr":         "memAddr",
    "mem_aligned_addr": "memAlignedAddr",
    "mem_rdata":        "memRData",
    "mem_wdata":        "memWData",
    "mem_wmask":        "memWMask",
}


class FlowCluster(CPU):
    cluster_profile      = "single"
    core_preset          = "gshare"
    privilege_profile    = "mcu"
    category             = "softcore"
    family               = "riscv"
    name                 = "flow_cluster"
    human_name           = "FlowCluster"
    variants             = CPU_VARIANTS
    data_width           = 64
    endianness           = "little"
    gcc_triple           = CPU_GCC_TRIPLE_RISCV64
    linker_output_format = "elf64-littleriscv"
    nop                  = "nop"
    mem_map              = {
        "rom"      : 0x1000_0000,
        "sram"     : 0x1100_0000,
        "csr"      : 0x1200_0000,
        "main_ram" : 0x8000_0000,
    }
    io_regions           = {
        0x0200_0000: 0x0001_0000,  # Machine timer.
        0x0c00_0000: 0x0400_0000,  # PLIC.
        0x1200_0000: 0x0100_0000,  # LiteX MMIO window.
    }

    @property
    def gcc_flags(self):
        flags = "-mno-save-restore "
        flags += GCC_FLAGS[self.variant]
        flags += " -D__flow__ "
        flags += "-mcmodel=medany"
        return flags

    @property
    def num_harts(self):
        return CLUSTER_NUM_HARTS[self.cluster_profile]

    def __init__(self, platform, variant="minimal"):
        self.platform     = platform
        self.variant      = variant
        self.human_name   = f"FlowCluster-{self.cluster_profile.upper()}-{self.core_preset.upper()}"
        num_harts         = self.num_harts
        self.reset        = Signal()
        # Memory master: coherent/cached traffic served by the L2/Home.
        self.ibus         = ibus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        # MMIO master: per-hart uncached requests behind the arbiter.
        self.dbus         = dbus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.periph_buses = [ibus, dbus] # Memory + MMIO masters.
        self.memory_buses = [] # No bus bypasses the shared LiteX interconnect.
        self.interrupt    = Signal(8)
        # Per-hart CLINT inputs, driven by the SoC-side BreezeClint slave.
        self.msip         = Signal(num_harts)
        self.mtip         = Signal(num_harts)
        self.time         = Signal(64)
        self.meip         = Signal(num_harts)
        self.seip         = Signal(num_harts)
        self.hart_fatal   = Signal(num_harts)
        self.hart_estop   = Signal(num_harts)
        self.retires      = [Record(RETIRE_LAYOUT) for _ in range(num_harts)]
        # Hart0 alias keeps single-profile monitors source-compatible.
        self.retire       = self.retires[0]

        self.cpu_params = dict(
            # Clk / Rst.
            i_clock = ClockSignal("sys"),
            i_reset = ResetSignal("sys") | self.reset,
            i_io_resetAddr = Constant(0, 64),
            i_io_time = self.time,

            # Memory Wishbone master (L2/Home side).
            o_io_memoryWishbone_adr   = ibus.adr,
            o_io_memoryWishbone_dat_w = ibus.dat_w,
            i_io_memoryWishbone_dat_r = ibus.dat_r,
            o_io_memoryWishbone_sel   = ibus.sel,
            o_io_memoryWishbone_cyc   = ibus.cyc,
            o_io_memoryWishbone_stb   = ibus.stb,
            i_io_memoryWishbone_ack   = ibus.ack,
            o_io_memoryWishbone_we    = ibus.we,
            o_io_memoryWishbone_cti   = ibus.cti,
            o_io_memoryWishbone_bte   = ibus.bte,
            i_io_memoryWishbone_err   = ibus.err,

            # MMIO Wishbone master (arbiter side).
            o_io_mmioWishbone_adr   = dbus.adr,
            o_io_mmioWishbone_dat_w = dbus.dat_w,
            i_io_mmioWishbone_dat_r = dbus.dat_r,
            o_io_mmioWishbone_sel   = dbus.sel,
            o_io_mmioWishbone_cyc   = dbus.cyc,
            o_io_mmioWishbone_stb   = dbus.stb,
            i_io_mmioWishbone_ack   = dbus.ack,
            o_io_mmioWishbone_we    = dbus.we,
            o_io_mmioWishbone_cti   = dbus.cti,
            o_io_mmioWishbone_bte   = dbus.bte,
            i_io_mmioWishbone_err   = dbus.err,
        )

        for hart in range(num_harts):
            self.cpu_params[f"i_io_msip_{hart}"] = self.msip[hart]
            self.cpu_params[f"i_io_mtip_{hart}"] = self.mtip[hart]
            if self.privilege_profile == "linux":
                self.cpu_params[f"i_io_externalInterrupts_{hart}"] = (
                    Cat(self.meip[hart], Constant(0, 7)))
                self.cpu_params[f"i_io_supervisorExternalInterrupts_{hart}"] = self.seip[hart]
            else:
                self.cpu_params[f"i_io_externalInterrupts_{hart}"] = (
                    self.interrupt if hart == 0 else Constant(0, 8))
                self.cpu_params[f"i_io_supervisorExternalInterrupts_{hart}"] = Constant(0)
            self.cpu_params[f"o_io_hartFatal_{hart}"] = self.hart_fatal[hart]
            self.cpu_params[f"o_io_hartEStop_{hart}"] = self.hart_estop[hart]
            for field_name, _ in RETIRE_LAYOUT:
                # Verilog keeps the Chisel camelCase names (rdWriteEn, ...).
                self.cpu_params[f"o_io_retire_{hart}_{RETIRE_RTL_NAMES[field_name]}"] = (
                    getattr(self.retires[hart], field_name))

        self.add_sources(platform)

    @classmethod
    def set_cluster_config(cls, cluster_profile, core_preset, privilege_profile="mcu"):
        if cluster_profile not in CLUSTER_PROFILES:
            expected = " or ".join(CLUSTER_PROFILES)
            raise ValueError(
                f"Unsupported cluster profile {cluster_profile!r}; expected {expected}")
        if core_preset not in CORE_PRESETS:
            expected = " or ".join(CORE_PRESETS)
            raise ValueError(
                f"Unsupported core preset {core_preset!r}; expected {expected}")
        if privilege_profile not in ("mcu", "linux"):
            raise ValueError("privilege profile must be 'mcu' or 'linux'")
        cls.cluster_profile = cluster_profile
        cls.core_preset = core_preset
        cls.privilege_profile = privilege_profile
        cls.mem_map = {
            "rom": 0x1001_0000 if privilege_profile == "linux" else 0x1000_0000,
            "sram": 0x1100_0000,
            "csr": 0x1200_0000,
            "main_ram": 0x8000_0000,
        }
        cls.io_regions = {
            0x0200_0000: 0x0001_0000,
            0x0c00_0000: 0x0400_0000,
            0x1200_0000: 0x0100_0000,
        }
        if privilege_profile == "linux":
            cls.io_regions[0x1000_0000] = 0x0000_0100

    def set_reset_address(self, reset_address):
        self.reset_address = reset_address
        self.cpu_params.update(i_io_resetAddr=Constant(reset_address, 64))

    @classmethod
    def flow_root_dir(cls):
        current_dir = os.path.dirname(os.path.abspath(__file__))
        return os.path.dirname(os.path.dirname(current_dir))

    @classmethod
    def rtl_dir(cls):
        base = os.path.join(
            cls.flow_root_dir(), "design", "build", "rtl", "cluster",
            cls.cluster_profile, cls.core_preset)
        return base if cls.privilege_profile == "mcu" else os.path.join(base, cls.privilege_profile)

    @classmethod
    def add_sources(cls, platform):
        rtl_dir = cls.rtl_dir()
        filelist = os.path.join(rtl_dir, "filelist.f")
        profile_marker = os.path.join(rtl_dir, "cluster-profile.txt")
        if not os.path.exists(filelist):
            raise FileNotFoundError(
                "Breeze cluster RTL manifest has not been elaborated. Expected:\n"
                f"  {filelist}\n"
                "Generate it with:\n"
                f"  cd {os.path.join(cls.flow_root_dir(), 'design')} && "
                f"sbt \"runMain flow.top.GenerateBreezeMulticoreClusterWishbone "
                f"{cls.cluster_profile} {cls.core_preset} {cls.privilege_profile}\""
            )
        if not os.path.isfile(profile_marker):
            raise FileNotFoundError(
                "Breeze cluster RTL profile marker is missing:\n"
                f"  {profile_marker}\n"
                "Regenerate the selected cluster RTL before simulation."
            )

        # Cross-check the elaborated profile against this wrapper instance:
        # never trust a marker the wrapper printed itself.
        values = {}
        with open(profile_marker, encoding="utf-8") as marker_file:
            for line in marker_file:
                line = line.strip()
                if not line or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip()
        expected = {
            "profile": cls.cluster_profile,
            "numHarts": str(CLUSTER_NUM_HARTS[cls.cluster_profile]),
            "l1iBytes": "8192",
            "l1dBytes": "8192",
            "l2Bytes": str(CLUSTER_L2_BYTES[cls.cluster_profile]),
            "lineBytes": "32",
            "l1Ways": "4",
            "l2Ways": "8",
            "corePreset": cls.core_preset,
            "privilegeProfile": cls.privilege_profile,
        }
        mismatches = {
            key: (values.get(key), value)
            for key, value in expected.items()
            if values.get(key) != value
        }
        if mismatches:
            detail = ", ".join(
                f"{key}: expected={value} actual={actual}"
                for key, (actual, value) in sorted(mismatches.items()))
            raise RuntimeError(
                f"Breeze cluster profile mismatch: {detail}")

        with open(filelist, encoding="utf-8") as rtl_manifest:
            manifest_entries = [
                line.split("#", 1)[0].strip()
                for line in rtl_manifest
                if line.split("#", 1)[0].strip()
            ]

        if not manifest_entries:
            raise RuntimeError(f"Breeze cluster RTL manifest is empty: {filelist}")

        include_paths = [
            entry[len("+incdir+"):]
            for entry in manifest_entries
            if entry.startswith("+incdir+")
        ]
        rtl_names = [
            entry for entry in manifest_entries
            if not entry.startswith("+incdir+")
        ]
        rtl_files = [os.path.join(rtl_dir, name) for name in rtl_names]
        missing_files = [path for path in rtl_files if not os.path.isfile(path)]
        if missing_files:
            missing = "\n".join(f"  {path}" for path in missing_files)
            raise FileNotFoundError(
                "Breeze cluster RTL manifest references missing files:\n" + missing
            )

        for include_path in include_paths:
            if not os.path.isdir(include_path):
                raise FileNotFoundError(
                    f"Breeze cluster RTL include path is missing: {include_path}")
            platform.add_verilog_include_path(include_path)

        for rtl_file in rtl_files:
            platform.add_source(rtl_file)

    def do_finalize(self):
        assert hasattr(self, "reset_address")
        self.specials += Instance("BreezeMulticoreClusterWishbone", **self.cpu_params)

"""AXI cluster products for LiteX; geometry is read from the generated marker."""
import hashlib
import os

from migen import Cat, ClockSignal, Constant, Instance, Record, ResetSignal, Signal
from litex.soc.cores.cpu import CPU, CPU_GCC_TRIPLE_RISCV64
from litex.soc.interconnect import axi
from .axi_router import BreezeAxiRouter, platform_regions

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
    ("rd_pending", 1), ("rd_is_fp", 1),
    ("late_write_valid", 1), ("late_write_is_fp", 1), ("late_write_error", 1),
    ("late_write_rd", 5), ("late_write_data", 64),
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
    "rd_pending": "rdPending", "rd_is_fp": "rdIsFp",
    "late_write_valid": "lateWriteValid", "late_write_is_fp": "lateWriteIsFp",
    "late_write_error": "lateWriteError", "late_write_rd": "lateWriteRd",
    "late_write_data": "lateWriteData",
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


L1D_EVENT_NAMES = "load_access load_miss store_access store_miss upgrade ptw_access ptw_miss hit_under_miss mshr_busy_cycles mshr_full_stall same_line_stall s0_conflict_stall writeback_dirty writeback_clean probe_received probe_held_cycles lr_count sc_fail mmio_read mmio_write mmio_cycles".split()
L2_VECTOR_NAMES = "req hit needProbe miss slotFullStall setWait put probeSent".split()


class _BreezeClusterCPU(CPU):
    category = "softcore"
    family = "riscv"
    data_width = 64
    endianness = "little"
    gcc_triple = CPU_GCC_TRIPLE_RISCV64
    linker_output_format = "elf64-littleriscv"
    nop = "nop"
    expected_marker = {}
    debug_enabled = False
    tandem_enabled = False
    io_regions = {0x02000000: 0x10000, 0x0c000000: 0x4000000, 0x12000000: 0x1000000}

    @property
    def gcc_flags(self):
        return f"-march={self.gcc_arch} -mabi={self.gcc_abi} -mno-save-restore -mcmodel=medany {self.gcc_defines}"

    def __init__(self, platform, variant="standard"):
        if variant not in self.variants:
            raise ValueError(f"Unsupported Breeze variant {variant!r}; expected {self.variants}")
        self.platform, self.variant = platform, variant
        marker_path = os.path.join(self.rtl_dir(), "cluster-profile.txt")
        self.add_sources(platform)
        marker = self._read_profile_marker(marker_path)
        self.id_width = int(marker["idBits"])
        self.l2_bytes = self.num_harts * int(marker["l2BytesPerCore"])
        self.reset = Signal()
        self.memory_bus = axi.AXIInterface(data_width=64, address_width=32, id_width=self.id_width)
        self.mmio_bus = axi.AXILiteInterface(data_width=64, address_width=32)
        self.submodules.axi_router = BreezeAxiRouter(self.memory_bus, platform_regions(self.flow_root_dir()))
        # Only the DRAM exit is eligible for LiteX's direct native connection.
        # The low-bandwidth exit and MMIO use LiteX's own protocol adapters.
        self.memory_buses = [self.axi_router.dram]
        self.periph_buses = [self.axi_router.low, self.mmio_bus]
        self.ibus, self.dbus = self.memory_bus, self.mmio_bus
        self.interrupt = Signal(8)
        self.msip, self.mtip = Signal(self.num_harts), Signal(self.num_harts)
        self.time = Signal(64)
        self.meip, self.seip = Signal(self.num_harts), Signal(self.num_harts)
        self.hart_fatal, self.hart_estop = Signal(self.num_harts), Signal(self.num_harts)
        self.retires = [Record(RETIRE_LAYOUT) for _ in range(self.num_harts)]
        self.retire = self.retires[0]
        self.cpu_params = dict(i_clock=ClockSignal("sys"), i_reset=ResetSignal("sys") | self.reset,
            i_io_resetAddr=Constant(self.reset_vector, 64), i_io_time=self.time)
        for prefix, bus in (("mem", self.memory_bus), ("mmio", self.mmio_bus)):
            for channel in ("ar", "aw", "w", "r", "b"):
                endpoint = getattr(bus, channel)
                outgoing = channel in ("ar", "aw", "w")
                self.cpu_params[("o" if outgoing else "i") + f"_io_{prefix}_{channel}_valid"] = endpoint.valid
                self.cpu_params[("i" if outgoing else "o") + f"_io_{prefix}_{channel}_ready"] = endpoint.ready
                fields = {"ar": ("addr", "prot"), "aw": ("addr", "prot"),
                          "w": ("data", "strb"), "r": ("data", "resp"), "b": ("resp",)}[channel]
                if prefix == "mem":
                    fields += {"ar": ("id", "len", "size", "burst"), "aw": ("id", "len", "size", "burst"),
                               "w": ("last",), "r": ("id", "last"), "b": ("id",)}[channel]
                for field in fields:
                    # Chisel AXI-Lite B carries UInt rather than a Bundle.
                    suffix = "bits" if prefix == "mmio" and channel == "b" else "bits_" + field
                    self.cpu_params[("o" if outgoing else "i") + f"_io_{prefix}_{channel}_{suffix}"] = getattr(endpoint, field)
        for hart in range(self.num_harts):
            self.cpu_params[f"i_io_msip_{hart}"] = self.msip[hart]
            self.cpu_params[f"i_io_mtip_{hart}"] = self.mtip[hart]
            self.cpu_params[f"i_io_externalInterrupts_{hart}"] = (
                Cat(self.meip[hart], Constant(0, 7)) if self.privilege_profile == "linux" else
                self.interrupt if hart == 0 else Constant(0, 8))
            self.cpu_params[f"i_io_supervisorExternalInterrupts_{hart}"] = (
                self.seip[hart] if self.privilege_profile == "linux" else Constant(0))
            self.cpu_params[f"o_io_hartFatal_{hart}"] = self.hart_fatal[hart]
            self.cpu_params[f"o_io_hartEStop_{hart}"] = self.hart_estop[hart]
        if self.debug_enabled:
            self.hang_threshold = int(marker["hangThresholdCycles"])
            self.debug = Record([( "lastRetirePc", 64), ("lastRetireInst", 32), ("seenRetire", 1),
                                 ("noRetireCycles", 32), ("hang", 1), ("hangReasons", 15)])
            for name, _ in self.debug.layout:
                self.cpu_params["o_io_debug_" + name] = getattr(self.debug, name)
            self.hang = self.debug.hang
            for name, _ in RETIRE_LAYOUT:
                self.cpu_params["o_io_debug_retire_" + RETIRE_RTL_NAMES[name]] = getattr(self.retire, name)
            self.l1d_events = Record([(n, 1) for n in L1D_EVENT_NAMES])
            for name in L1D_EVENT_NAMES:
                self.cpu_params["o_io_debug_l1dEvents_" + name] = getattr(self.l1d_events, name)
            self.l2_events = []
            for name in L2_VECTOR_NAMES:
                count = self.num_harts if name in ("put", "probeSent") else 2 * self.num_harts + 1
                for i in range(count):
                    signal = Signal(name="l2_" + name + "_" + str(i))
                    self.l2_events.append((name + "_" + str(i), signal))
                    self.cpu_params[f"o_io_debug_l2Events_{name}_{i}"] = signal
            for name, width in (("probeCycles", 1), ("memRead", 1),
                ("memReadsInFlight", int(marker.get("l2Slots", "2")).bit_length()),
                ("memTwoInflight", 1), ("memWrite", 1)):
                signal = Signal(width, name="l2_" + name)
                self.l2_events.append((name, signal))
                self.cpu_params["o_io_debug_l2Events_" + name] = signal

    def set_reset_address(self, reset_address):
        if reset_address != self.reset_vector:
            raise ValueError(f"Breeze reset address is fixed: expected {self.reset_vector:#x}, got {reset_address:#x}")
        self.reset_address = reset_address
        self.cpu_params["i_io_resetAddr"] = Constant(reset_address, 64)

    @classmethod
    def flow_root_dir(cls):
        return os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))

    @classmethod
    def rtl_dir(cls):
        return os.path.join(cls.flow_root_dir(), "design", "build", "rtl", "axi-cluster",
            cls.cluster_profile, cls.core_preset, cls.privilege_profile,
            "tandem" if cls.tandem_enabled else "production", "fpga-debug" if cls.debug_enabled else "cpu")

    @classmethod
    def _read_profile_marker(cls, marker_path):
        with open(marker_path, encoding="utf-8") as f:
            lines = [line.strip() for line in f if line.strip()]
        pairs = [line.split("=", 1) for line in lines]
        if any(len(p) != 2 for p in pairs) or len({p[0] for p in pairs}) != len(pairs):
            raise RuntimeError("invalid or duplicate cluster marker fields")
        return dict(pairs)

    @classmethod
    def _validate_profile_marker(cls, marker_path):
        values = cls._read_profile_marker(marker_path)
        with open(os.path.join(cls.flow_root_dir(), "config", "breeze_mcu_platform.json"), "rb") as f:
            platform_hash = hashlib.sha256(f.read()).hexdigest()
        expected = {"bus": "axi", "profile": cls.cluster_profile, "preset": cls.core_preset,
            "privilege": cls.privilege_profile, "tandem": str(cls.tandem_enabled).lower(),
            "debug": str(cls.debug_enabled).lower(), "platformSha256": platform_hash,
            "nCores": str(cls.num_harts), **cls.expected_marker}
        mismatches = [f"{key}: expected={value} actual={values.get(key)}"
                      for key, value in expected.items() if values.get(key) != value]
        if mismatches:
            raise RuntimeError("Breeze cluster profile mismatch: " + ", ".join(mismatches))
        for key in ("idBits", "l2BytesPerCore", "l2Slots"):
            if int(values.get(key, "0")) <= 0:
                raise RuntimeError(f"invalid cluster geometry marker: {key}")
        if cls.debug_enabled and int(values.get("hangThresholdCycles", "0")) <= 0:
            raise RuntimeError("invalid hangThresholdCycles")

    @classmethod
    def add_sources(cls, platform):
        rtl_dir = cls.rtl_dir()
        filelist = os.path.join(rtl_dir, "filelist.f")
        marker = os.path.join(rtl_dir, "cluster-profile.txt")
        if not os.path.isfile(filelist) or not os.path.isfile(marker):
            raise FileNotFoundError("Breeze RTL missing; cd design && sbt 'runMain flow.top.GenerateBreezeCluster " +
                f"{cls.cluster_profile} {cls.core_preset} {cls.privilege_profile}" +
                (" tandem" if cls.tandem_enabled else "") + (" fpga-debug" if cls.debug_enabled else "") + "'")
        cls._validate_profile_marker(marker)
        with open(filelist, encoding="utf-8") as f:
            entries = [line.split("#", 1)[0].strip() for line in f if line.split("#", 1)[0].strip()]
        if not entries:
            raise RuntimeError("Breeze RTL manifest is empty")
        for entry in entries:
            include = entry.startswith("+incdir+")
            path = entry[len("+incdir+"):] if include else entry
            path = path if os.path.isabs(path) else os.path.join(rtl_dir, path)
            if not (os.path.isdir(path) if include else os.path.isfile(path)):
                raise FileNotFoundError(f"Breeze RTL manifest references missing file: {path}")
            if include:
                platform.add_verilog_include_path(path)
            else:
                platform.add_source(path)

    def do_finalize(self):
        if not hasattr(self, "reset_address"):
            raise RuntimeError("LiteX did not assign the Breeze reset address")
        self.specials += Instance("BreezeClusterAxi", **self.cpu_params)


class Breeze(_BreezeClusterCPU):
    name = "breeze"
    human_name = "Breeze RV64GC (4 harts, AXI)"
    variants = ("standard",)
    cluster_profile = "small"
    num_harts = 4
    core_preset = "gshare"
    privilege_profile = "linux"
    rtl_mode = "production"
    gcc_arch = "rv64imafdc_zicsr_zifencei"
    gcc_abi = "lp64d"
    gcc_defines = "-D__breeze__ -D__riscv_plic__"
    reset_vector = 0x10010000
    _regions = platform_regions()
    mem_map = {"rom": _regions["linux_boot_rom"]["origin"], "sram": _regions["sram"]["origin"],
               "csr": _regions["litex_mmio"]["origin"], "main_ram": _regions["main_ram"]["origin"]}


class BreezeTiny(Breeze):
    name = "breeze-tiny"
    human_name = "Breeze Tiny RV64GC (1 hart, AXI)"
    cluster_profile = "single"
    num_harts = 1


class BreezeTinyDebug(BreezeTiny):
    human_name = "Breeze Tiny RV64GC (1 hart, AXI debug)"
    rtl_mode = "fpga-debug"
    tandem_enabled = True
    debug_enabled = True

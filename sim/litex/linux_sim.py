#!/usr/bin/env python3
"""Build/run the four-hart Flow Linux SoC with host-loaded DDR images."""

import argparse
import hashlib
import os
import subprocess
import sys

from migen import Display, Finish, If, Module, Signal
from litex.build.sim.config import SimConfig
from litex.soc.integration.builder import Builder
from litex.soc.integration.common import get_mem_data

SIM_DIR = os.path.dirname(os.path.abspath(__file__))
FLOW_ROOT = os.path.abspath(os.path.join(SIM_DIR, "..", ".."))
if SIM_DIR not in sys.path:
    sys.path.insert(0, SIM_DIR)

from multicore_sim import MTIME_FREQUENCY_HZ, MulticoreSimSoC  # noqa: E402
from flow.wiring import wishbone_byte_address  # noqa: E402


DDR_BASE = 0x8000_0000
DDR_SIZE = 0x1000_0000
OPENSBI_ADDR = 0x8000_0000
DTB_ADDR = 0x8010_0000
KERNEL_ADDR = 0x8020_0000
ROM_SIZE = 0x0001_0000
LINUX_SYS_CLK_FREQUENCY_HZ = 50_000_000


class LinuxBootMonitor(Module):
    """Bounded, opt-in retirement trace for Linux bring-up failures."""

    def __init__(self, cpu, mmio_bus, max_cycles, log_limit=64,
                 progress_cycles=1_000_000):
        cycle = Signal(64)
        logged = Signal(32)
        uart_reads_logged = Signal(6)
        progress = Signal(max=progress_cycles, reset=progress_cycles - 1)
        retire_counts = [Signal(64) for _ in cpu.retires]
        last_pcs = [Signal(64) for _ in cpu.retires]
        uart_address = Signal(64)
        uart_access = Signal()
        self.comb += [
            uart_address.eq(wishbone_byte_address(
                mmio_bus.adr, mmio_bus.data_width)),
            uart_access.eq(
                (uart_address >= 0x1200_1000) &
                (uart_address < 0x1200_2000)),
        ]
        statements = [
            cycle.eq(cycle + 1),
            If(progress == 0,
                progress.eq(progress_cycles - 1),
                Display("[LINUX-PROGRESS] cycle=%d", cycle),
                *[
                    Display(
                        f"[LINUX-PROGRESS-HART] hart={hart} retires=%d last_pc=0x%x",
                        retire_counts[hart], last_pcs[hart])
                    for hart in range(len(cpu.retires))
                ]).Else(progress.eq(progress - 1)),
            If(mmio_bus.ack & mmio_bus.we & uart_access,
                Display(
                    "[LINUX-UART-MMIO] cycle=%d addr=0x%x adr=0x%x "
                    "sel=0x%x data=0x%x",
                    cycle, uart_address, mmio_bus.adr,
                    mmio_bus.sel, mmio_bus.dat_w)),
            If(mmio_bus.ack & ~mmio_bus.we & uart_access &
                    (uart_reads_logged < 32),
                Display(
                    "[LINUX-UART-READ] cycle=%d addr=0x%x adr=0x%x "
                    "sel=0x%x data=0x%x",
                    cycle, uart_address, mmio_bus.adr,
                    mmio_bus.sel, mmio_bus.dat_r),
                uart_reads_logged.eq(uart_reads_logged + 1)),
        ]
        for hart, retire in enumerate(cpu.retires):
            statements.append(
                If(retire.valid,
                    retire_counts[hart].eq(retire_counts[hart] + 1),
                    last_pcs[hart].eq(retire.pc),
                    If(logged < log_limit,
                        Display(
                            f"[LINUX-RETIRE] hart={hart} cycle=%d pc=0x%x "
                            "inst=0x%x next=0x%x",
                            cycle, retire.pc, retire.inst, retire.next_pc),
                        logged.eq(logged + 1))))
            statements.append(
                If(cpu.hart_fatal[hart],
                    Display(f"[LINUX-FATAL] hart={hart} cycle=%d", cycle),
                    Finish()))
        statements.append(
            If(cycle == max_cycles,
                Display("[LINUX-TIMEOUT] cycle=%d", cycle),
                *[
                    Display(
                        f"[LINUX-SUMMARY] hart={hart} retires=%d last_pc=0x%x",
                        retire_counts[hart], last_pcs[hart])
                    for hart in range(len(cpu.retires))
                ],
                Finish()))
        self.sync += statements


def _read(path):
    with open(path, "rb") as handle:
        return handle.read()


def _sha256(data):
    return hashlib.sha256(data).hexdigest()


def pack_ddr(segments):
    occupied = []
    end = DDR_BASE
    loaded = []
    for name, address, path in segments:
        data = _read(path)
        first = address
        last = address + len(data)
        if first < DDR_BASE or last > DDR_BASE + DDR_SIZE:
            raise ValueError(f"{name} does not fit in DDR: 0x{first:x}..0x{last:x}")
        for old_name, old_first, old_last in occupied:
            if first < old_last and old_first < last:
                raise ValueError(f"{name} overlaps {old_name} in DDR")
        occupied.append((name, first, last))
        loaded.append((name, address, data, path))
        end = max(end, last)

    image = bytearray(end - DDR_BASE)
    for name, address, data, path in loaded:
        offset = address - DDR_BASE
        image[offset:offset + len(data)] = data
        print(
            f"BREEZE_LINUX_IMAGE name={name} address=0x{address:08x} "
            f"size={len(data)} sha256={_sha256(data)} path={path}",
            flush=True,
        )
    image.extend(b"\0" * ((-len(image)) % 4))
    return [int.from_bytes(image[n:n + 4], "little") for n in range(0, len(image), 4)]


def elaborate(profile, core_preset, rtl_mode):
    sbt = os.environ.get("SBT", "sbt")
    command = [
        sbt,
        "runMain flow.top.GenerateBreezeMulticoreClusterWishbone "
        f"{profile} {core_preset} linux {rtl_mode}",
    ]
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=os.path.join(FLOW_ROOT, "design"), check=True)


def main():
    parser = argparse.ArgumentParser(description="Build/run the Flow Linux LiteDRAM simulation.")
    parser.add_argument("--opensbi", required=True,
        help="OpenSBI fw_jump.bin linked for 0x80000000 and jumping to 0x80200000.")
    parser.add_argument("--kernel", required=True,
        help="Linux Image; the Buildroot/Alpine initramfs is embedded in this image.")
    parser.add_argument("--dtb", required=True, help="Flow device tree blob.")
    parser.add_argument("--bootrom", required=True, help="Flow reset ROM raw binary.")
    parser.add_argument("--profile", choices=("single", "dual", "small"), default="small")
    parser.add_argument("--core-preset", choices=("gshare", "baseline"), default="gshare")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--elaborate", action="store_true")
    parser.add_argument("--rtl-mode", choices=("production", "debug"),
        default="production",
        help="Cluster RTL mode used by --elaborate; debug enables Tandem.")
    parser.add_argument("--build", action="store_true",
        help="Compile and run the Verilator simulation after generating it.")
    parser.add_argument("--trace", action="store_true")
    parser.add_argument("--non-interactive", action="store_true")
    parser.add_argument("--opt-level", choices=("O0", "O1", "O2", "O3"),
        default="O3", help="Host compiler optimization for Verilator (default: O3).")
    parser.add_argument("--jobs", type=int, default=os.cpu_count(),
        help="Parallel Verilator build jobs (default: host CPU count).")
    parser.add_argument("--debug-cycles", type=int, default=0,
        help="Stop after N cycles and print initial per-hart retirements (0 disables).")
    parser.add_argument("--mem-trace", action="store_true",
        help="Enable passive Tandem/DCache/Wishbone memory tracing.")
    parser.add_argument("--mem-trace-max-events", type=int, default=1024)
    parser.add_argument("--mem-trace-address-start", type=lambda value: int(value, 0))
    parser.add_argument("--mem-trace-address-end", type=lambda value: int(value, 0))
    parser.add_argument("--compact-event-trace", action="store_true",
        help="Emit an unbounded compact [FLOW-EVENT] stream for host-side capture.")
    parser.add_argument("--cycle-debug", action="store_true",
        help="Emit periodic combined [FLOW-CYCLE] progress snapshots.")
    parser.add_argument("--cycle-debug-interval", type=int, default=1,
        help=("Emit [FLOW-CYCLE] once per N cycles when --cycle-debug is set "
              "(default: 1)."))
    parser.add_argument("--fault-retire-trace", action="store_true",
        help=("Keep a silent retirement ring, dump it on a positive-user to "
              "negative-address transfer or exact PC match, then stop."))
    parser.add_argument("--fault-retire-depth", type=int, default=64,
        help="Number of pre-trigger retirements retained per hart (default: 64).")
    parser.add_argument("--fault-retire-pc", type=lambda value: int(value, 0),
        help="Optional exact 64-bit PC/next-PC trigger, such as an Oops badaddr.")
    args = parser.parse_args()

    for path in (args.opensbi, args.kernel, args.dtb, args.bootrom):
        if not os.path.isfile(path):
            parser.error(f"image does not exist: {path}")
    if args.debug_cycles and args.rtl_mode != "debug":
        parser.error("--debug-cycles requires --rtl-mode debug")
    if args.fault_retire_trace and args.rtl_mode != "debug":
        parser.error("--fault-retire-trace requires --rtl-mode debug")
    if args.fault_retire_pc is not None and not args.fault_retire_trace:
        parser.error("--fault-retire-pc requires --fault-retire-trace")
    if args.cycle_debug_interval <= 0:
        parser.error("--cycle-debug-interval must be greater than zero")
    if not 1 <= args.fault_retire_depth <= 1024:
        parser.error("--fault-retire-depth must be between 1 and 1024")
    if args.fault_retire_pc is not None and not 0 <= args.fault_retire_pc < (1 << 64):
        parser.error("--fault-retire-pc must fit in 64 bits")
    if args.elaborate:
        elaborate(args.profile, args.core_preset, args.rtl_mode)
    if args.mem_trace_max_events <= 0:
        parser.error("--mem-trace-max-events must be greater than zero")
    if (args.mem_trace_address_start is not None and
            args.mem_trace_address_end is not None and
            args.mem_trace_address_start >= args.mem_trace_address_end):
        parser.error("memory trace address start must be below its end")

    ddr_init = pack_ddr([
        ("opensbi", OPENSBI_ADDR, args.opensbi),
        ("dtb", DTB_ADDR, args.dtb),
        ("kernel", KERNEL_ADDR, args.kernel),
    ])
    rom_init = get_mem_data(
        args.bootrom,
        data_width=64,
        endianness="little",
        mem_size=ROM_SIZE,
    )

    sim_config = SimConfig()
    sim_config.add_clocker("sys_clk", freq_hz=LINUX_SYS_CLK_FREQUENCY_HZ)
    sim_config.add_module("serial2console", "serial")
    soc = MulticoreSimSoC(
        sys_clk_freq=LINUX_SYS_CLK_FREQUENCY_HZ,
        rom_init=rom_init,
        cluster_profile=args.profile,
        core_preset=args.core_preset,
        privilege_profile="linux",
        with_litedram=True,
        sdram_init=ddr_init,
        memory_trace=args.mem_trace,
        memory_trace_max_events=args.mem_trace_max_events,
        memory_trace_address_start=args.mem_trace_address_start,
        memory_trace_address_end=args.mem_trace_address_end,
        compact_event_trace=args.compact_event_trace,
        cycle_debug=args.cycle_debug,
        cycle_debug_interval=args.cycle_debug_interval,
        fault_retire_trace=args.fault_retire_trace,
        fault_retire_depth=args.fault_retire_depth,
        fault_retire_pc=args.fault_retire_pc,
    )
    if args.debug_cycles < 0:
        parser.error("--debug-cycles must not be negative")
    if args.jobs is None or args.jobs <= 0:
        parser.error("--jobs must be greater than zero")
    if args.debug_cycles:
        soc.submodules.linux_boot_monitor = LinuxBootMonitor(
            soc.cpu, soc.cpu.dbus, args.debug_cycles)
    print(
        f"BREEZE_LINUX_SOC harts={soc.cpu.num_harts} ram=0x{DDR_BASE:08x}+0x{DDR_SIZE:x} "
        f"sys_clk_hz={LINUX_SYS_CLK_FREQUENCY_HZ} "
        f"timebase_hz={MTIME_FREQUENCY_HZ} "
        f"opensbi=0x{OPENSBI_ADDR:08x} dtb=0x{DTB_ADDR:08x} kernel=0x{KERNEL_ADDR:08x}",
        flush=True,
    )
    builder = Builder(soc, output_dir=args.output_dir, compile_software=False)
    builder.build(
        run=args.build,
        sim_config=sim_config,
        trace=args.trace,
        opt_level=args.opt_level,
        jobs=args.jobs,
        interactive=not args.non_interactive,
    )


if __name__ == "__main__":
    main()

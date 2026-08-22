#!/usr/bin/env python3
"""Project-owned LiteX simulation target for the Breeze multicore cluster.

Mirrors breeze_sim.py, but instantiates one BreezeMulticoreClusterWishbone
through the FlowCluster CPU wrapper (spec section 21). Completion checking
reuses the MCU completion monitor on hart 0's retire trace; the pass/fail
label is the frozen multicore marker text.
"""

import argparse
import os
import sys

from litex.build.io import CRG
from litex.build.sim.config import SimConfig
from litex.soc.cores.cpu import CPUS
from litex.soc.integration.builder import Builder
from litex.soc.integration.common import get_mem_data
from litex.soc.integration.soc import SoCRegion
from litex.soc.integration.soc_core import SoCCore
from litedram.modules import MT41K64M16
from litedram.phy.model import SDRAMPHYModel, sdram_module_nphases


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LITEX_WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if LITEX_WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, LITEX_WRAPPER_ROOT)
SIM_DIR = os.path.dirname(os.path.abspath(__file__))
if SIM_DIR not in sys.path:
    sys.path.insert(0, SIM_DIR)

from flow.cluster import (  # noqa: E402
    CLUSTER_L2_BYTES, CLUSTER_NUM_HARTS, CLUSTER_PROFILES, CORE_PRESETS,
    FlowCluster,
)
from flow.clint import BreezeClint  # noqa: E402
from flow.clint_verilog import BreezeClintVerilog  # noqa: E402
from flow.plic import BreezePlic  # noqa: E402
from flow.plic_verilog import BreezePlicVerilog  # noqa: E402
from flow.wiring import pack_plic_sources  # noqa: E402
from breeze_sim import (  # noqa: E402
    MACHINE_TIMER_ORIGIN, MACHINE_TIMER_SIZE, MSIP_OFFSET, MTIME_FREQUENCY_HZ,
    MTIMECMP_OFFSET, MTIME_OFFSET, PLIC_ORIGIN, PLIC_SIZE,
    McuCompletionMonitor, Platform,
)
from memory_monitor import FlowMemoryMonitor  # noqa: E402
from interrupt_monitor import FlowInterruptChainMonitor  # noqa: E402


# Do not rely on LiteX's current-working-directory based CPU discovery.
CPUS["flow_cluster"] = FlowCluster


class MulticoreSimSoC(SoCCore):
    """Breeze cluster SoC matching breeze_mcu_platform.json."""

    mem_map = {
        "rom"      : 0x1000_0000,
        "sram"     : 0x1100_0000,
        "csr"      : 0x1200_0000,
        "main_ram" : 0x8000_0000,
    }
    csr_map = {
        "ctrl" : 0,
        "uart" : 1,
    }
    interrupt_map = {
        "uart"  : 0,
        "gpio0" : 1,
        "gpio1" : 2,
        "gpio2" : 3,
        "gpio3" : 4,
    }

    def __init__(self, sys_clk_freq=int(1e6), rom_init=None,
                 cluster_profile="single", core_preset="gshare",
                 privilege_profile="mcu",
                 with_litedram=False,
                 sdram_init=None,
                 completion_label=None, mcu_result_address=None,
                 mcu_perf_address=None, mcu_timeout=20000,
                 memory_trace=False, memory_trace_max_events=1024,
                 memory_trace_address_start=None,
                 memory_trace_address_end=None, **kwargs):
        platform = Platform()
        FlowCluster.set_cluster_config(cluster_profile, core_preset, privilege_profile)
        self.mem_map = dict(type(self).mem_map)
        if privilege_profile == "linux":
            self.mem_map["rom"] = 0x1001_0000
        # LiteX's CRG supplies the power-on reset pulse required by the
        # synchronous-reset Chisel core.
        self.submodules.crg = CRG(platform.request("sys_clk"))

        super().__init__(
            platform,
            clk_freq=sys_clk_freq,
            ident="",
            cpu_type="flow_cluster",
            cpu_variant="minimal",
            bus_standard="wishbone",
            bus_data_width=64,
            bus_address_width=32,
            bus_bursting=False,
            bus_interconnect="shared",
            integrated_rom_size=0x0001_0000,
            integrated_rom_init=[] if rom_init is None else rom_init,
            integrated_sram_size=0x0004_0000,
            integrated_main_ram_size=0 if with_litedram else 0x0200_0000,
            csr_data_width=32,
            csr_address_width=14,
            csr_paging=0x1000,
            with_ctrl=True,
            # MCU and Linux deliberately share LiteX's native LiteUART.  This
            # keeps the simulated and future FPGA UART/CSR implementation on
            # one maintained path instead of carrying a private 16550 model.
            with_uart=True,
            uart_name="sim",
            with_timer=False,
            **kwargs,
        )

        # Linux simulations use the same LiteDRAM controller path that a
        # future FPGA target will use, backed here by a behavioral DDR3 PHY.
        # The Flow cluster already owns the coherent L2, so do not insert a
        # second LiteX L2 cache in front of memory.
        if with_litedram:
            sdram_clk_freq = int(sys_clk_freq)
            sdram_rate = "1:{}".format(sdram_module_nphases[MT41K64M16.memtype])
            sdram_module = MT41K64M16(sdram_clk_freq, sdram_rate)
            self.submodules.sdrphy = SDRAMPHYModel(
                module=sdram_module,
                data_width=32,
                clk_freq=sdram_clk_freq,
                init=[] if sdram_init is None else sdram_init,
            )
            self.add_sdram(
                name="sdram",
                phy=self.sdrphy,
                module=sdram_module,
                size=0x1000_0000,
                l2_cache_size=0,
            )
            self.add_constant("BREEZE_LITEDRAM", 1)

        # Linux uses standalone CLINT RTL; retain the old Migen block for the
        # established MCU regressions and as a reference during migration.
        clint_class = (
            BreezeClintVerilog if privilege_profile == "linux" else BreezeClint)
        clint_args = dict(
            sys_clk_freq=sys_clk_freq,
            timebase_freq=MTIME_FREQUENCY_HZ,
            num_harts=self.cpu.num_harts,
            region_size=MACHINE_TIMER_SIZE,
            msip_offset=MSIP_OFFSET,
            mtimecmp_offset=MTIMECMP_OFFSET,
            mtime_offset=MTIME_OFFSET,
        )
        if privilege_profile == "linux":
            clint_args["platform"] = platform
        self.submodules.machine_timer = clint_class(**clint_args)
        self.bus.add_slave(
            name="machine_timer",
            slave=self.machine_timer.bus,
            region=SoCRegion(
                origin=MACHINE_TIMER_ORIGIN,
                size=MACHINE_TIMER_SIZE,
                cached=False,
            ),
        )
        self.comb += [
            self.cpu.mtip.eq(self.machine_timer.mtip),
            self.cpu.msip.eq(self.machine_timer.msip),
            self.cpu.time.eq(self.machine_timer.mtime),
        ]
        # Keep the legacy Migen PLIC for MCU regressions.  Linux uses the
        # standalone SystemVerilog implementation through a thin LiteX wrapper
        # so priority/enable/claim behavior is verified as real RTL rather than
        # being intertwined with Migen SoC elaboration.
        if privilege_profile == "linux":
            self.submodules.plic = BreezePlicVerilog(
                platform, num_harts=self.cpu.num_harts, num_sources=31)
        else:
            self.submodules.plic = BreezePlic(
                num_harts=self.cpu.num_harts, num_sources=31)
        self.bus.add_slave(
            name="plic", slave=self.plic.bus,
            region=SoCRegion(origin=PLIC_ORIGIN, size=PLIC_SIZE, cached=False))
        # LiteUART is CSR page 1 at 0x1200_1000.  Linux receives only this
        # device on PLIC source 10; do not alias LiteX's complete internal IRQ
        # vector into one architecturally described PLIC source.
        linux_uart_irq = self.uart.ev.irq if privilege_profile == "linux" else 0
        self.comb += [
            self.plic.sources.eq(pack_plic_sources(linux_uart_irq)),
            self.cpu.meip.eq(self.plic.meip),
            self.cpu.seip.eq(self.plic.seip),
        ]
        self.add_constant("BREEZE_MSIP", MACHINE_TIMER_ORIGIN + MSIP_OFFSET)
        self.add_constant("BREEZE_MTIME", MACHINE_TIMER_ORIGIN + MTIME_OFFSET)
        self.add_constant("BREEZE_MTIMECMP", MACHINE_TIMER_ORIGIN + MTIMECMP_OFFSET)
        self.add_constant("BREEZE_MTIME_FREQUENCY", MTIME_FREQUENCY_HZ)

        if completion_label is not None:
            if mcu_result_address is None:
                raise ValueError(
                    "MCU completion checks require the result symbol address")
            if mcu_perf_address is None:
                raise ValueError(
                    "MCU completion checks require the PMU snapshot symbol address")
            self.submodules.mcu_completion_monitor = McuCompletionMonitor(
                retire=self.cpu.retire,
                result_address=mcu_result_address,
                perf_address=mcu_perf_address,
                check_kind="generic",
                timeout_cycles=mcu_timeout,
                label=completion_label,
            )
            if completion_label.endswith("LITEUART-PLIC"):
                self.submodules.interrupt_chain_monitor = (
                    FlowInterruptChainMonitor(
                        uart=self.uart,
                        plic=self.plic,
                        cpu=self.cpu,
                        retire=self.cpu.retires[0],
                        source_id=10,
                        hart=0,
                        context=0,
                    )
                )

        if memory_trace:
            trace_buses = [
                ("cpu-mmio", self.cpu.dbus),
                ("cpu-memory", self.cpu.ibus),
            ]
            self.submodules.memory_monitor = FlowMemoryMonitor(
                self.cpu,
                wishbone_buses=trace_buses,
                max_events=memory_trace_max_events,
                address_start=memory_trace_address_start,
                address_end=memory_trace_address_end,
            )


def main():
    parser = argparse.ArgumentParser(
        description="Build or run the Breeze multicore cluster LiteX simulation.")
    parser.add_argument("--profile", choices=CLUSTER_PROFILES, required=True,
        help="Cluster profile: single (1 hart), dual (2 harts) or small (4 harts).")
    parser.add_argument("--core-preset", choices=CORE_PRESETS, default="gshare",
        help="Core RTL preset (default: gshare; baseline is explicit).")
    parser.add_argument("--privilege", choices=("mcu", "linux"), default="mcu")
    parser.add_argument("--with-litedram", action="store_true",
        help="Replace the 32 MiB integrated RAM with a 256 MiB modeled DDR3/LiteDRAM backend.")
    parser.add_argument("--test-name", required=True,
        help="Registered multicore test name; forms the completion marker label.")
    parser.add_argument("--rom-init", required=True,
        help="Raw binary loaded at the ROM base address (0x10000000).")
    parser.add_argument("--mcu-result-address", type=lambda value: int(value, 0),
        required=True, help="Address of __breeze_result from the firmware ELF.")
    parser.add_argument("--mcu-perf-address", type=lambda value: int(value, 0),
        required=True, help="Address of __breeze_pmu_snapshot from the firmware ELF.")
    parser.add_argument("--mcu-timeout", type=int, default=20000,
        help="Cycles before the firmware check times out (default: 20000).")
    parser.add_argument("--output-dir", required=True,
        help="LiteX output directory (one per profile/preset/test).")
    parser.add_argument("--build", action="store_true",
        help="Run Verilator after generating the LiteX build tree.")
    parser.add_argument("--trace", action="store_true",
        help="Enable simulator waveform tracing.")
    parser.add_argument("--non-interactive", action="store_true",
        help="Run without attaching simulator stdin to a terminal.")
    parser.add_argument("--mem-trace", action="store_true",
        help="Enable passive Tandem/DCache/Wishbone memory tracing.")
    parser.add_argument("--mem-trace-max-events", type=int, default=1024,
        help="Maximum traced transactions per observer (default: 1024).")
    parser.add_argument("--mem-trace-address-start", type=lambda value: int(value, 0),
        help="Inclusive byte-address filter for memory tracing.")
    parser.add_argument("--mem-trace-address-end", type=lambda value: int(value, 0),
        help="Exclusive byte-address filter for memory tracing.")
    args = parser.parse_args()

    if args.mcu_timeout <= 0:
        parser.error("--mcu-timeout must be greater than zero")
    if args.mem_trace_max_events <= 0:
        parser.error("--mem-trace-max-events must be greater than zero")
    if (args.mem_trace_address_start is not None and
            args.mem_trace_address_end is not None and
            args.mem_trace_address_start >= args.mem_trace_address_end):
        parser.error("memory trace address start must be below its end")

    label = f"MULTICORE-{args.profile.upper()}-{args.test_name.upper()}"
    print(
        "BREEZE_CLUSTER_CONFIG "
        f"profile={args.profile} harts={CLUSTER_NUM_HARTS[args.profile]} "
        f"core_preset={args.core_preset} "
        f"l2_bytes={CLUSTER_L2_BYTES[args.profile]}",
        flush=True,
    )

    sim_config = SimConfig()
    sim_config.add_clocker("sys_clk", freq_hz=int(1e6))
    sim_config.add_module("serial2console", "serial")

    rom_init = get_mem_data(
        args.rom_init,
        data_width=64,
        endianness="little",
        mem_size=0x0001_0000,
    )

    soc = MulticoreSimSoC(
        rom_init=rom_init,
        cluster_profile=args.profile,
        core_preset=args.core_preset,
        privilege_profile=args.privilege,
        with_litedram=args.with_litedram,
        completion_label=label,
        mcu_result_address=args.mcu_result_address,
        mcu_perf_address=args.mcu_perf_address,
        mcu_timeout=args.mcu_timeout,
        memory_trace=args.mem_trace,
        memory_trace_max_events=args.mem_trace_max_events,
        memory_trace_address_start=args.mem_trace_address_start,
        memory_trace_address_end=args.mem_trace_address_end,
    )
    builder = Builder(soc, output_dir=args.output_dir, compile_software=False)
    builder.build(
        run=args.build,
        sim_config=sim_config,
        trace=args.trace,
        interactive=not args.non_interactive,
    )


if __name__ == "__main__":
    main()

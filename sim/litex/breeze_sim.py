#!/usr/bin/env python3
"""Project-owned LiteX simulation target for BreezeCore."""

import argparse
import json
import os
import sys

from migen import ClockDomain, Module

from litex.build.generic_platform import Pins, Subsignal
from litex.build.sim import SimPlatform
from litex.build.sim.config import SimConfig
from litex.soc.cores.cpu import CPUS
from litex.soc.integration.builder import Builder
from litex.soc.integration.common import get_mem_data
from litex.soc.integration.soc import SoCRegion
from litex.soc.integration.soc_core import SoCCore


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LITEX_WRAPPER_ROOT = os.path.join(FLOW_ROOT, "litex_wrapper")
if LITEX_WRAPPER_ROOT not in sys.path:
    sys.path.insert(0, LITEX_WRAPPER_ROOT)

from flow.core import Flow
from flow.machine_timer import BreezeMachineTimer


# Do not rely on LiteX's current-working-directory based CPU discovery.
CPUS["flow"] = Flow


def _platform_int(value):
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise TypeError(f"Platform integer must be an int or string, got {type(value)}")


with open(os.path.join(FLOW_ROOT, "config", "breeze_mcu_platform.json"),
          encoding="utf-8") as platform_config_file:
    PLATFORM_CONFIG = json.load(platform_config_file)

MACHINE_TIMER_CONFIG = PLATFORM_CONFIG["machineTimer"]
MACHINE_TIMER_REGION = next(
    region for region in PLATFORM_CONFIG["regions"]
    if region["name"] == MACHINE_TIMER_CONFIG["region"]
)
MACHINE_TIMER_ORIGIN = _platform_int(MACHINE_TIMER_REGION["origin"])
MACHINE_TIMER_SIZE = _platform_int(MACHINE_TIMER_REGION["size"])
MTIMECMP_OFFSET = _platform_int(MACHINE_TIMER_CONFIG["mtimecmpOffset"])
MTIME_OFFSET = _platform_int(MACHINE_TIMER_CONFIG["mtimeOffset"])
MTIME_FREQUENCY_HZ = _platform_int(MACHINE_TIMER_CONFIG["mtimeFrequencyHz"])


_IO = [
    ("sys_clk", 0, Pins(1)),
    ("serial", 0,
        Subsignal("source_valid", Pins(1)),
        Subsignal("source_ready", Pins(1)),
        Subsignal("source_data", Pins(8)),
        Subsignal("sink_valid", Pins(1)),
        Subsignal("sink_ready", Pins(1)),
        Subsignal("sink_data", Pins(8))),
]


class Platform(SimPlatform):
    def __init__(self):
        super().__init__("SIM", _IO)


class SimCRG(Module):
    def __init__(self, sys_clk):
        self.clock_domains.cd_sys = ClockDomain()
        self.comb += self.cd_sys.clk.eq(sys_clk)


class BreezeSimSoC(SoCCore):
    """Minimal Breeze MCU SoC matching breeze_mcu_platform.json."""

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

    def __init__(self, sys_clk_freq=int(1e6), rom_init=None, **kwargs):
        platform = Platform()
        self.submodules.crg = SimCRG(platform.request("sys_clk"))

        super().__init__(
            platform,
            clk_freq=sys_clk_freq,
            ident="",
            cpu_type="flow",
            cpu_variant="minimal",
            bus_standard="wishbone",
            bus_data_width=64,
            bus_address_width=32,
            bus_bursting=False,
            bus_interconnect="shared",
            integrated_rom_size=0x0001_0000,
            integrated_rom_init=[] if rom_init is None else rom_init,
            integrated_sram_size=0x0004_0000,
            integrated_main_ram_size=0x0200_0000,
            csr_data_width=32,
            csr_address_width=14,
            csr_paging=0x1000,
            with_ctrl=True,
            with_uart=True,
            uart_name="sim",
            with_timer=False,
            **kwargs,
        )

        self.submodules.machine_timer = BreezeMachineTimer(
            sys_clk_freq=sys_clk_freq,
            timebase_freq=MTIME_FREQUENCY_HZ,
            region_size=MACHINE_TIMER_SIZE,
            mtime_offset=MTIME_OFFSET,
            mtimecmp_offset=MTIMECMP_OFFSET,
        )
        self.bus.add_slave(
            name="machine_timer",
            slave=self.machine_timer.bus,
            region=SoCRegion(
                origin=MACHINE_TIMER_ORIGIN,
                size=MACHINE_TIMER_SIZE,
                cached=False,
            ),
        )
        self.comb += self.cpu.mtip.eq(self.machine_timer.mtip)
        self.add_constant("BREEZE_MTIME", MACHINE_TIMER_ORIGIN + MTIME_OFFSET)
        self.add_constant("BREEZE_MTIMECMP", MACHINE_TIMER_ORIGIN + MTIMECMP_OFFSET)
        self.add_constant("BREEZE_MTIME_FREQUENCY", MTIME_FREQUENCY_HZ)


def main():
    parser = argparse.ArgumentParser(
        description="Build or run the minimal BreezeCore LiteX simulation.")
    parser.add_argument("--build", action="store_true",
        help="Run Verilator after generating the LiteX build tree.")
    parser.add_argument("--trace", action="store_true",
        help="Enable simulator waveform tracing.")
    parser.add_argument("--rom-init",
        help="Raw binary loaded at the ROM base address (0x10000000).")
    parser.add_argument("--output-dir", default="build/litex-sim",
        help="LiteX output directory (default: build/litex-sim).")
    args = parser.parse_args()

    sim_config = SimConfig()
    sim_config.add_clocker("sys_clk", freq_hz=int(1e6))
    sim_config.add_module("serial2console", "serial")

    rom_init = get_mem_data(
        args.rom_init,
        data_width=64,
        endianness="little",
        mem_size=0x0001_0000,
    )
    soc = BreezeSimSoC(rom_init=rom_init)
    builder = Builder(soc, output_dir=args.output_dir, compile_software=False)
    builder.build(
        run=args.build,
        sim_config=sim_config,
        trace=args.trace,
    )


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Project-owned LiteX simulation target for BreezeCore."""

import argparse
import json
import os
import sys

from migen import Display, Finish, If, Module, Signal

from litex.build.generic_platform import Pins, Subsignal
from litex.build.io import CRG
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
RESET_VECTOR = _platform_int(PLATFORM_CONFIG["resetVector"])

IBUS_DATA_WIDTH = 64
ICACHE_LINE_BYTES = 32


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


class FetchWishboneMonitor(Module):
    """Finite first-refill diagnostic for the simulation instruction bus."""

    def __init__(self, ibus, reset_vector, expected_first_word,
                 timeout_cycles=100, line_bytes=ICACHE_LINE_BYTES,
                 stop_after_first_fetch=False):
        beat_bytes = len(ibus.dat_r) // 8
        if len(ibus.dat_r) != IBUS_DATA_WIDTH:
            raise ValueError(
                f"Fetch monitor requires a {IBUS_DATA_WIDTH}-bit instruction bus")
        if line_bytes <= 0 or line_bytes % beat_bytes != 0:
            raise ValueError("ICache line size must contain complete bus beats")
        if timeout_cycles <= 0:
            raise ValueError("Fetch timeout must be greater than zero")

        beat_count = line_bytes // beat_bytes
        expected_word_address = reset_vector // beat_bytes

        cycle = Signal(32)
        previous_active = Signal()
        beat_index = Signal(max=max(2, beat_count))
        refill_complete = Signal()
        active = Signal()
        byte_address = Signal(len(ibus.adr) + 3)
        expected_word_address_signal = Signal(len(ibus.adr))
        expected_first_word_signal = Signal(len(ibus.dat_r))

        self.comb += [
            active.eq(ibus.cyc & ibus.stb),
            byte_address.eq(ibus.adr << 3),
            expected_word_address_signal.eq(expected_word_address),
            expected_first_word_signal.eq(expected_first_word),
        ]

        completion_statements = [
            Display("[IFETCH-PASS] first %d-byte cacheline returned", line_bytes),
            refill_complete.eq(1),
            beat_index.eq(0),
        ]
        if stop_after_first_fetch:
            completion_statements.append(Finish())

        self.sync += [
            cycle.eq(cycle + 1),
            previous_active.eq(active),

            If(~refill_complete & active & ~previous_active,
                Display(
                    "[IFETCH-REQ] cycle=%d word_addr=0x%x byte_addr=0x%x",
                    cycle, ibus.adr, byte_address),
                If(ibus.adr != expected_word_address_signal,
                    Display(
                        "[IFETCH-FAIL] wrong reset fetch address "
                        "expected=0x%x actual=0x%x",
                        expected_word_address_signal, ibus.adr),
                    Finish()
                )
            ),

            If(~refill_complete & active & ibus.err,
                Display(
                    "[IFETCH-FAIL] Wishbone error cycle=%d "
                    "word_addr=0x%x byte_addr=0x%x",
                    cycle, ibus.adr, byte_address),
                Finish()
            ).Elif(~refill_complete & active & ibus.ack,
                Display(
                    "[IFETCH-ACK] cycle=%d beat=%d word_addr=0x%x data=0x%x",
                    cycle, beat_index, ibus.adr, ibus.dat_r),
                If((beat_index == 0) &
                   (ibus.dat_r != expected_first_word_signal),
                    Display(
                        "[IFETCH-FAIL] first ROM word mismatch "
                        "expected=0x%x actual=0x%x",
                        expected_first_word_signal, ibus.dat_r),
                    Finish()
                ).Elif(beat_index == (beat_count - 1),
                    *completion_statements
                ).Else(
                    beat_index.eq(beat_index + 1)
                )
            ),

            If((cycle == (timeout_cycles - 1)) & ~refill_complete,
                Display(
                    "[IFETCH-TIMEOUT] cycle=%d cyc=%d stb=%d ack=%d err=%d "
                    "word_addr=0x%x received_beats=%d",
                    cycle, ibus.cyc, ibus.stb, ibus.ack, ibus.err,
                    ibus.adr, beat_index),
                Finish()
            )
        ]


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

    def __init__(self, sys_clk_freq=int(1e6), rom_init=None,
                 debug_fetch=False, fetch_timeout=100,
                 stop_after_first_fetch=False, **kwargs):
        platform = Platform()
        # LiteX's CRG supplies the power-on reset pulse required by the
        # synchronous-reset Chisel core. A clock-only domain leaves the core's
        # architectural state, including its reset PC, uninitialized.
        self.submodules.crg = CRG(platform.request("sys_clk"))

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

        if debug_fetch:
            if not rom_init:
                raise ValueError("Fetch debug requires a non-empty ROM image")
            self.submodules.fetch_monitor = FetchWishboneMonitor(
                ibus=self.cpu.ibus,
                reset_vector=RESET_VECTOR,
                expected_first_word=rom_init[0],
                timeout_cycles=fetch_timeout,
                stop_after_first_fetch=stop_after_first_fetch,
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
    parser.add_argument("--debug-fetch", action="store_true",
        help="Print and validate the first instruction-cache refill.")
    parser.add_argument("--fetch-timeout", type=int, default=100,
        help="Cycles before an incomplete first refill fails (default: 100).")
    parser.add_argument("--stop-after-first-fetch", action="store_true",
        help="Finish simulation after the first valid ICache line is returned.")
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
    if args.stop_after_first_fetch and not args.debug_fetch:
        parser.error("--stop-after-first-fetch requires --debug-fetch")
    if args.debug_fetch and not rom_init:
        parser.error("--debug-fetch requires a non-empty --rom-init image")
    if args.fetch_timeout <= 0:
        parser.error("--fetch-timeout must be greater than zero")

    soc = BreezeSimSoC(
        rom_init=rom_init,
        debug_fetch=args.debug_fetch,
        fetch_timeout=args.fetch_timeout,
        stop_after_first_fetch=args.stop_after_first_fetch,
    )
    builder = Builder(soc, output_dir=args.output_dir, compile_software=False)
    builder.build(
        run=args.build,
        sim_config=sim_config,
        trace=args.trace,
    )


if __name__ == "__main__":
    main()

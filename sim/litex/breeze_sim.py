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

SMOKE_MAIN_RAM_ADDRESS = 0x8000_0000
SMOKE_MEMORY_PATTERN = 0x0123_4567_89ab_cdef
SMOKE_INITIAL_SP = 0x1104_0000
MCU_PASS_MAGIC = 0x4252_4545_5a45_5001
MCU_FAIL_MAGIC = 0x4252_4545_5a45_f001
MRET_INSTRUCTION = 0x3020_0073


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


class RetireMonitor(Module):
    """Finite retirement and smoke-memory checks for SoC bring-up."""

    def __init__(self, retire, expected_first_pc, expected_first_inst,
                 first_retire_timeout=200, stop_after_first_retire=False,
                 check_smoke_memory=False, memory_timeout=10000,
                 retire_log_limit=64, retire_stall_timeout=500):
        if first_retire_timeout <= 0:
            raise ValueError("First-retire timeout must be greater than zero")
        if memory_timeout <= 0:
            raise ValueError("Memory timeout must be greater than zero")
        if retire_log_limit < 0:
            raise ValueError("Retire log limit must not be negative")
        if retire_stall_timeout <= 0:
            raise ValueError("Retire-stall timeout must be greater than zero")

        cycle = Signal(32)
        memory_cycle = Signal(32)
        retire_count = Signal(32)
        retire_number = Signal(32)
        retire_idle_cycles = Signal(32)
        first_retire_seen = Signal()
        smoke_store_seen = Signal()
        smoke_memory_complete = Signal()
        last_retire_pc = Signal(64)
        last_retire_inst = Signal(32)
        last_memory_seen = Signal()
        last_memory_is_write = Signal()
        last_memory_address = Signal(64)

        expected_first_pc_signal = Signal(64)
        expected_first_inst_signal = Signal(32)
        expected_initial_sp_signal = Signal(64)
        smoke_main_ram_address_signal = Signal(64)
        smoke_memory_pattern_signal = Signal(64)

        self.comb += [
            expected_first_pc_signal.eq(expected_first_pc),
            expected_first_inst_signal.eq(expected_first_inst),
            expected_initial_sp_signal.eq(SMOKE_INITIAL_SP),
            smoke_main_ram_address_signal.eq(SMOKE_MAIN_RAM_ADDRESS),
            smoke_memory_pattern_signal.eq(SMOKE_MEMORY_PATTERN),
            retire_number.eq(retire_count + 1),
        ]

        first_retire_success = (
            (retire.pc == expected_first_pc_signal) &
            (retire.inst == expected_first_inst_signal)
        )
        if check_smoke_memory:
            first_retire_success = (
                first_retire_success &
                retire.rd_write_en &
                (retire.rd_addr == 2) &
                (retire.rd_data == expected_initial_sp_signal)
            )

        target_memory_retire = (
            retire.valid & retire.mem_en &
            (retire.mem_addr == smoke_main_ram_address_signal)
        )

        first_success_statements = [
            Display("[RETIRE-PASS] first instruction retired"),
            first_retire_seen.eq(1),
        ]
        if stop_after_first_retire:
            first_success_statements.append(Finish())

        memory_success_statements = [
            Display("[MEM-PASS] smoke SD/LD returned expected data"),
            smoke_memory_complete.eq(1),
            Finish(),
        ]

        self.sync += [
            cycle.eq(cycle + 1),
            If(first_retire_seen & ~smoke_memory_complete,
                memory_cycle.eq(memory_cycle + 1)
            ),

            If(retire.valid,
                retire_count.eq(retire_number),
                retire_idle_cycles.eq(0),
                last_retire_pc.eq(retire.pc),
                last_retire_inst.eq(retire.inst),
                If(retire_count < retire_log_limit,
                    Display(
                        "[RETIRE] count=%d cycle=%d pc=0x%x inst=0x%x "
                        "rd_we=%d rd=x%d rd_data=0x%x",
                        retire_number, cycle, retire.pc, retire.inst,
                        retire.rd_write_en, retire.rd_addr, retire.rd_data)
                ),
                If(retire.mem_en,
                    last_memory_seen.eq(1),
                    last_memory_is_write.eq(retire.mem_is_write),
                    last_memory_address.eq(retire.mem_addr),
                    Display(
                        "[MEM-RETIRE] count=%d cycle=%d pc=0x%x "
                        "write=%d addr=0x%x rdata=0x%x "
                        "wdata=0x%x mask=0x%x",
                        retire_number, cycle, retire.pc,
                        retire.mem_is_write, retire.mem_addr,
                        retire.mem_rdata, retire.mem_wdata,
                        retire.mem_wmask)
                )
            ).Elif(first_retire_seen & ~smoke_memory_complete,
                retire_idle_cycles.eq(retire_idle_cycles + 1)
            ),

            If(retire.valid & ~first_retire_seen,
                Display(
                    "[RETIRE-FIRST] cycle=%d pc=0x%x inst=0x%x "
                    "rd_we=%d rd=x%d rd_data=0x%x",
                    cycle, retire.pc, retire.inst, retire.rd_write_en,
                    retire.rd_addr, retire.rd_data),
                If(~first_retire_success,
                    Display(
                        "[RETIRE-FAIL] expected pc=0x%x inst=0x%x",
                        expected_first_pc_signal, expected_first_inst_signal),
                    Finish()
                ).Else(
                    *first_success_statements
                )
            ),

            If(~first_retire_seen & ~retire.valid &
               (cycle >= (first_retire_timeout - 1)),
                Display(
                    "[RETIRE-TIMEOUT] no instruction retired within cycle=%d",
                    cycle),
                Finish()
            ),
        ]

        if check_smoke_memory:
            self.sync += [
                If(first_retire_seen & target_memory_retire &
                   retire.mem_is_write,
                    Display(
                        "[MEM-STORE] cycle=%d pc=0x%x addr=0x%x "
                        "data=0x%x mask=0x%x",
                        cycle, retire.pc, retire.mem_addr,
                        retire.mem_wdata, retire.mem_wmask),
                    If(smoke_store_seen |
                       (retire.mem_wdata != smoke_memory_pattern_signal) |
                       (retire.mem_wmask != 0xff),
                        Display("[MEM-FAIL] unexpected smoke SD retirement"),
                        Finish()
                    ).Else(
                        smoke_store_seen.eq(1)
                    )
                ),

                If(first_retire_seen & target_memory_retire &
                   ~retire.mem_is_write,
                    Display(
                        "[MEM-LOAD] cycle=%d pc=0x%x addr=0x%x "
                        "mem_data=0x%x rd_data=0x%x",
                        cycle, retire.pc, retire.mem_addr,
                        retire.mem_rdata, retire.rd_data),
                    If(~smoke_store_seen |
                       ~retire.rd_write_en |
                       (retire.mem_rdata != smoke_memory_pattern_signal) |
                       (retire.rd_data != smoke_memory_pattern_signal),
                        Display("[MEM-FAIL] unexpected smoke LD retirement"),
                        Finish()
                    ).Else(
                        *memory_success_statements
                    )
                ),

                If(first_retire_seen & ~smoke_memory_complete &
                   ~target_memory_retire &
                   (memory_cycle >= (memory_timeout - 1)),
                    Display(
                        "[MEM-TIMEOUT] cycle=%d retire_count=%d "
                        "last_pc=0x%x last_inst=0x%x store_seen=%d "
                        "last_mem_valid=%d last_mem_write=%d "
                        "last_mem_addr=0x%x",
                        cycle, retire_count, last_retire_pc,
                        last_retire_inst, smoke_store_seen,
                        last_memory_seen, last_memory_is_write,
                        last_memory_address),
                    Finish()
                ),

                If(first_retire_seen & ~smoke_memory_complete &
                   ~retire.valid &
                   (retire_idle_cycles >= (retire_stall_timeout - 1)),
                    Display(
                        "[RETIRE-STALL] cycle=%d idle_cycles=%d "
                        "retire_count=%d last_pc=0x%x last_inst=0x%x "
                        "last_mem_valid=%d last_mem_write=%d "
                        "last_mem_addr=0x%x",
                        cycle, retire_idle_cycles, retire_count,
                        last_retire_pc, last_retire_inst,
                        last_memory_seen, last_memory_is_write,
                        last_memory_address),
                    Finish()
                )
            ]


class McuCompletionMonitor(Module):
    """Finite pass/fail monitor for the reusable MCU firmware runtime."""

    def __init__(self, retire, result_address, perf_address, check_kind="generic", irq_source=None,
                 expected_vector_pc=None, timeout_cycles=20000):
        if check_kind not in ("generic", "timer", "uart"):
            raise ValueError(f"Unsupported MCU completion kind: {check_kind}")
        if timeout_cycles <= 0:
            raise ValueError("MCU completion timeout must be greater than zero")
        if check_kind != "generic" and irq_source is None:
            raise ValueError("Interrupt completion checks require an IRQ source")

        label = check_kind.upper()
        cycle = Signal(32)
        source_seen = Signal(reset=0)
        vector_seen = Signal(reset=0)
        mret_seen = Signal(reset=0)
        measurement_active = Signal(reset=0)
        measured_cycles = Signal(64, reset=0)
        measured_instructions = Signal(64, reset=0)
        perf_values = [Signal(64, reset=0) for _ in range(10)]
        perf_seen = Signal(10, reset=0)
        result_address_signal = Signal(64)
        perf_address_signal = Signal(64)
        pass_magic = Signal(64)
        fail_magic = Signal(64)
        expected_vector = Signal(64)
        proof_complete = Signal()
        pmu_complete = Signal()

        proof_expression = 1
        if irq_source is not None:
            proof_expression = source_seen & mret_seen
        if expected_vector_pc is not None:
            proof_expression = proof_expression & vector_seen

        self.comb += [
            result_address_signal.eq(result_address),
            perf_address_signal.eq(perf_address),
            pass_magic.eq(MCU_PASS_MAGIC),
            fail_magic.eq(MCU_FAIL_MAGIC),
            expected_vector.eq(0 if expected_vector_pc is None else expected_vector_pc),
            proof_complete.eq(proof_expression),
            pmu_complete.eq(perf_seen == 0x3ff),
        ]

        result_store = (
            retire.valid & retire.mem_en & retire.mem_is_write &
            (retire.mem_addr == result_address_signal) &
            (retire.mem_wmask == 0xff)
        )
        for index, value in enumerate(perf_values):
            perf_store = (
                retire.valid & retire.mem_en & retire.mem_is_write &
                (retire.mem_addr == (perf_address_signal + 8 * index)) &
                (retire.mem_wmask == 0xff)
            )
            self.sync += If(perf_store,
                value.eq(retire.mem_wdata),
                perf_seen.eq(perf_seen | (1 << index))
            )

        self.sync += cycle.eq(cycle + 1)

        # The runtime's zero-valued completion store is the measurement arm
        # marker immediately before main().  Count only the cycles and retired
        # instructions strictly between that marker and the final PASS/FAIL
        # store, keeping performance reporting independent from UART output.
        self.sync += [
            If(result_store & (retire.mem_wdata == 0),
                measurement_active.eq(1),
                measured_cycles.eq(0),
                measured_instructions.eq(0)
            ).Elif(result_store & measurement_active,
                measurement_active.eq(0)
            ).Elif(measurement_active,
                measured_cycles.eq(measured_cycles + 1),
                If(retire.valid,
                    measured_instructions.eq(measured_instructions + 1)
                )
            )
        ]

        self.sync += If(result_store & measurement_active,
            Display(
                "BREEZE_PERF cycles=%d instructions=%d",
                measured_cycles, measured_instructions)
        )
        self.sync += If(result_store & measurement_active,
            If(pmu_complete,
                Display(
                    "BREEZE_PMU cycles=%d instructions=%d control=%d taken=%d "
                    "pred_miss=%d icache_miss=%d dcache_access=%d "
                    "dcache_miss=%d uncached=%d mem_stall=%d",
                    *perf_values)
            )
        )

        if irq_source is not None:
            self.sync += If(irq_source & ~source_seen,
                source_seen.eq(1),
                Display(f"[{label}-SRC] interrupt source asserted cycle=%d", cycle)
            )

        if expected_vector_pc is not None:
            self.sync += If(retire.valid & (retire.pc == expected_vector),
                vector_seen.eq(1),
                Display(
                    f"[{label}-VECTOR] expected vector slot retired "
                    "cycle=%d pc=0x%x", cycle, retire.pc)
            )

        if irq_source is None and expected_vector_pc is None:
            completion_success = [
                If(~pmu_complete,
                    Display(f"[{label}-FAIL] incomplete PMU snapshot mask=0x%x", perf_seen),
                    Finish()
                ).Else(
                    Display(f"[{label}-PASS] MCU firmware completed"),
                    Finish()
                )
            ]
            timeout_failure = [
                Display(f"[{label}-TIMEOUT] cycle=%d", cycle),
                Finish(),
            ]
        else:
            completion_success = [
                If(~proof_complete | ~pmu_complete,
                    Display(
                        f"[{label}-FAIL] incomplete proof source_seen=%d "
                        "vector_seen=%d mret_seen=%d pmu_mask=0x%x",
                        source_seen, vector_seen, mret_seen, perf_seen),
                    Finish()
                ).Else(
                    Display(f"[{label}-PASS] MCU firmware completed"),
                    Finish()
                )
            ]
            timeout_failure = [
                Display(
                    f"[{label}-TIMEOUT] cycle=%d source_seen=%d "
                    "vector_seen=%d mret_seen=%d",
                    cycle, source_seen, vector_seen, mret_seen),
                Finish(),
            ]

        self.sync += [
            If(retire.valid & (retire.inst == MRET_INSTRUCTION),
                mret_seen.eq(1),
                Display(f"[{label}-MRET] handler returned cycle=%d", cycle),
                *([
                    If(irq_source,
                        Display(
                            f"[{label}-FAIL] source still asserted at mret"),
                        Finish()
                    )
                ] if irq_source is not None else [])
            ),

            If(result_store,
                If(retire.mem_wdata == 0,
                    Display(f"[{label}-ARM] firmware runtime started")
                ).Elif(retire.mem_wdata == fail_magic,
                    Display(f"[{label}-FAIL] firmware reported failure"),
                    Finish()
                ).Elif(retire.mem_wdata != pass_magic,
                    Display(
                        f"[{label}-FAIL] unexpected completion signature 0x%x",
                        retire.mem_wdata),
                    Finish()
                ).Else(
                    *completion_success
                )
            ),

            If(cycle >= (timeout_cycles - 1),
                *timeout_failure
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
                 core_preset="baseline",
                 debug_fetch=False, fetch_timeout=100,
                 stop_after_first_fetch=False, debug_retire=False,
                 first_retire_timeout=200, stop_after_first_retire=False,
                 check_smoke_memory=False, memory_timeout=10000,
                 retire_log_limit=64, retire_stall_timeout=500,
                 check_mcu_completion=None, expected_trap_vector=None,
                 mcu_result_address=None, mcu_perf_address=None, mtvec_mode="direct",
                 mcu_timeout=20000, **kwargs):
        platform = Platform()
        Flow.set_core_preset(core_preset)
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

        if debug_retire:
            if not rom_init:
                raise ValueError("Retire debug requires a non-empty ROM image")
            self.submodules.retire_monitor = RetireMonitor(
                retire=self.cpu.retire,
                expected_first_pc=RESET_VECTOR,
                expected_first_inst=rom_init[0] & 0xffff_ffff,
                first_retire_timeout=first_retire_timeout,
                stop_after_first_retire=stop_after_first_retire,
                check_smoke_memory=check_smoke_memory,
                memory_timeout=memory_timeout,
                retire_log_limit=retire_log_limit,
                retire_stall_timeout=retire_stall_timeout,
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

        if check_mcu_completion is not None:
            if mcu_result_address is None:
                raise ValueError(
                    "MCU completion checks require the result symbol address")
            if mcu_perf_address is None:
                raise ValueError(
                    "MCU completion checks require the PMU snapshot symbol address")
            irq_source = None
            expected_vector_pc = None
            if check_mcu_completion == "timer":
                irq_source = self.machine_timer.mtip
                cause = 7
            elif check_mcu_completion == "uart":
                irq_source = self.cpu.interrupt[0]
                cause = 11
            elif check_mcu_completion != "generic":
                raise ValueError(
                    f"Unsupported MCU completion kind: {check_mcu_completion}")

            if check_mcu_completion != "generic":
                if expected_trap_vector is None:
                    raise ValueError(
                        "Interrupt completion checks require the trap vector base")
                vector_offset = 4 * cause if mtvec_mode == "vectored" else 0
                expected_vector_pc = expected_trap_vector + vector_offset

            self.submodules.mcu_completion_monitor = McuCompletionMonitor(
                retire=self.cpu.retire,
                result_address=mcu_result_address,
                perf_address=mcu_perf_address,
                check_kind=check_mcu_completion,
                irq_source=irq_source,
                expected_vector_pc=expected_vector_pc,
                timeout_cycles=mcu_timeout,
            )


def main():
    parser = argparse.ArgumentParser(
        description="Build or run the minimal BreezeCore LiteX simulation.")
    parser.add_argument("--build", action="store_true",
        help="Run Verilator after generating the LiteX build tree.")
    parser.add_argument("--trace", action="store_true",
        help="Enable simulator waveform tracing.")
    parser.add_argument("--non-interactive", action="store_true",
        help="Run without attaching simulator stdin to a terminal.")
    parser.add_argument("--rom-init",
        help="Raw binary loaded at the ROM base address (0x10000000).")
    parser.add_argument("--core-preset", choices=Flow.core_presets,
        default="baseline",
        help="BreezeCore RTL preset (default: baseline; GShare is opt-in).")
    parser.add_argument("--debug-fetch", action="store_true",
        help="Print and validate the first instruction-cache refill.")
    parser.add_argument("--fetch-timeout", type=int, default=100,
        help="Cycles before an incomplete first refill fails (default: 100).")
    parser.add_argument("--stop-after-first-fetch", action="store_true",
        help="Finish simulation after the first valid ICache line is returned.")
    parser.add_argument("--debug-retire", action="store_true",
        help="Validate and print the first architecturally retired instruction.")
    parser.add_argument("--first-retire-timeout", type=int, default=200,
        help="Cycles before a missing first retirement fails (default: 200).")
    parser.add_argument("--stop-after-first-retire", action="store_true",
        help="Finish simulation after validating the first retirement.")
    parser.add_argument("--check-smoke-memory", action="store_true",
        help="Validate the smoke firmware's main-RAM SD/LD and then finish.")
    parser.add_argument("--memory-timeout", type=int, default=10000,
        help="Cycles after first retirement before SD/LD check fails.")
    parser.add_argument("--retire-log-limit", type=int, default=64,
        help="Number of initial retirements printed (default: 64).")
    parser.add_argument("--retire-stall-timeout", type=int, default=500,
        help="Cycles without retirement before smoke check fails.")
    parser.add_argument("--check-mcu-completion",
        choices=("generic", "timer", "uart"),
        help="Run a finite reusable-MCU firmware completion check.")
    parser.add_argument("--expected-trap-vector", type=lambda value: int(value, 0),
        help="Address of breeze_trap_vector from the firmware ELF.")
    parser.add_argument("--mcu-result-address", type=lambda value: int(value, 0),
        help="Address of __breeze_result from the firmware ELF.")
    parser.add_argument("--mcu-perf-address", type=lambda value: int(value, 0),
        help="Address of __breeze_pmu_snapshot from the firmware ELF.")
    parser.add_argument("--mtvec-mode", choices=("direct", "vectored"),
        default="direct", help="Firmware mtvec mode (default: direct).")
    parser.add_argument("--mcu-timeout", type=int, default=20000,
        help="Cycles before an MCU firmware check times out (default: 20000).")
    parser.add_argument("--output-dir",
        help="LiteX output directory; defaults to build/litex-sim/<core-preset>.")
    args = parser.parse_args()

    output_dir = args.output_dir or os.path.join(
        "build", "litex-sim", args.core_preset)
    print(f"BREEZE_CONFIG core_preset={args.core_preset}", flush=True)

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
    if args.stop_after_first_retire and not args.debug_retire:
        parser.error("--stop-after-first-retire requires --debug-retire")
    if args.check_smoke_memory and not args.debug_retire:
        parser.error("--check-smoke-memory requires --debug-retire")
    if args.debug_retire and not rom_init:
        parser.error("--debug-retire requires a non-empty --rom-init image")
    if args.first_retire_timeout <= 0:
        parser.error("--first-retire-timeout must be greater than zero")
    if args.memory_timeout <= 0:
        parser.error("--memory-timeout must be greater than zero")
    if args.retire_log_limit < 0:
        parser.error("--retire-log-limit must not be negative")
    if args.retire_stall_timeout <= 0:
        parser.error("--retire-stall-timeout must be greater than zero")
    if args.mcu_timeout <= 0:
        parser.error("--mcu-timeout must be greater than zero")
    if args.check_mcu_completion in ("timer", "uart") and \
       args.expected_trap_vector is None:
        parser.error(
            "interrupt MCU checks require --expected-trap-vector")
    if args.check_mcu_completion and args.mcu_result_address is None:
        parser.error("MCU completion checks require --mcu-result-address")
    if args.check_mcu_completion and args.mcu_perf_address is None:
        parser.error("MCU completion checks require --mcu-perf-address")

    soc = BreezeSimSoC(
        rom_init=rom_init,
        core_preset=args.core_preset,
        debug_fetch=args.debug_fetch,
        fetch_timeout=args.fetch_timeout,
        stop_after_first_fetch=args.stop_after_first_fetch,
        debug_retire=args.debug_retire,
        first_retire_timeout=args.first_retire_timeout,
        stop_after_first_retire=args.stop_after_first_retire,
        check_smoke_memory=args.check_smoke_memory,
        memory_timeout=args.memory_timeout,
        retire_log_limit=args.retire_log_limit,
        retire_stall_timeout=args.retire_stall_timeout,
        check_mcu_completion=args.check_mcu_completion,
        expected_trap_vector=args.expected_trap_vector,
        mcu_result_address=args.mcu_result_address,
        mcu_perf_address=args.mcu_perf_address,
        mtvec_mode=args.mtvec_mode,
        mcu_timeout=args.mcu_timeout,
    )
    builder = Builder(soc, output_dir=output_dir, compile_software=False)
    builder.build(
        run=args.build,
        sim_config=sim_config,
        trace=args.trace,
        interactive=not args.non_interactive,
    )


if __name__ == "__main__":
    main()

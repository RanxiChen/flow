"""Passive native Vivado ILA for the single-hart FPGA debug product."""

import json

from migen import Cat, ClockSignal, Constant, If, Instance, Module, ResetSignal, Signal

from .core import DCACHE_TRACE_LAYOUT, RETIRE_LAYOUT


class BreezeDebugILA(Module):
    depth = 4096
    input_pipe_stages = 2

    def __init__(self, cpu, platform, clock_hz=50_000_000, extra_sources=(),
                 storage_qualifier=False, depth=4096):
        self.depth = depth
        self.storage_qualifier = storage_qualifier
        self.clock_hz = int(clock_hz)
        if cpu.num_harts != 1 or not cpu.tandem_enabled:
            raise ValueError("Breeze ILA requires a single hart with live Tandem outputs")

        retire = cpu.retires[0]
        last_pc = Signal(64)
        last_inst = Signal(32)
        seen_retire = Signal()
        idle_cycles = Signal(16)
        self.sync += If(retire.valid,
            last_pc.eq(retire.pc),
            last_inst.eq(retire.inst),
            seen_retire.eq(1),
            idle_cycles.eq(0),
        ).Elif(idle_cycles != 0xffff,
            idle_cycles.eq(idle_cycles + 1),
        )

        sources = [("reset", ResetSignal("sys"))]
        sources += [("retire_" + name, getattr(retire, name))
                    for name, _ in RETIRE_LAYOUT]
        sources += [
            ("last_retire_pc", last_pc),
            ("last_retire_inst", last_inst),
            ("seen_retire", seen_retire),
            ("no_retire_cycles", idle_cycles),
            ("hart_fatal", cpu.hart_fatal),
            ("hart_estop", cpu.hart_estop),
            ("mtime_low", cpu.time[:32]),
        ]
        sources += [("dcache_" + name, getattr(cpu.dcache_traces[0], name))
                    for name, _ in DCACHE_TRACE_LAYOUT]
        for prefix, bus in (("memory", cpu.memory_bus), ("mmio", cpu.mmio_bus)):
            # Both Breeze Wishbone masters use 64-bit word addressing.
            sources.append((prefix + "_address", Cat(Constant(0, 3), bus.adr)))
            sources += [(prefix + "_" + name, getattr(bus, name))
                        for name in ("cyc", "stb", "ack", "we", "err", "sel", "dat_w", "dat_r")]

        sources += list(extra_sources)
        self.probes = []
        self.probe_map = []
        params = {"i_clk": ClockSignal("sys")}
        for index, (name, source) in enumerate(sources):
            probe = Signal(len(source), name_override="dbg_" + name)
            probe.attr.add("keep")
            self.comb += probe.eq(source)
            self.probes.append(probe)
            self.probe_map.append({"port": "probe" + str(index),
                                   "signal": "dbg_" + name, "width": len(probe)})
            params["i_probe" + str(index)] = probe

        self.specials += Instance("breeze_ila", **params)
        commands = platform.toolchain.pre_synthesis_commands
        commands.append("create_ip -name ila -vendor xilinx.com -library ip "
                        "-version 6.2 -module_name breeze_ila")
        properties = {
            "C_NUM_OF_PROBES": len(self.probes),
            "C_DATA_DEPTH": self.depth,
            "C_INPUT_PIPE_STAGES": self.input_pipe_stages,
            "C_EN_STRG_QUAL": int(storage_qualifier),
            "ALL_PROBE_SAME_MU_CNT": 2 if storage_qualifier else 1,
        }
        properties.update({"C_PROBE%d_WIDTH" % i: len(probe)
                           for i, probe in enumerate(self.probes)})
        for name, value in properties.items():
            commands.append(f"set_property CONFIG.{name} {value} [get_ips breeze_ila]")
        commands.append("generate_target all [get_ips breeze_ila]")
        platform.toolchain.additional_commands.append(
            "write_debug_probes -force {build_name}.ltx")

    def write_probe_map(self, filename):
        with open(filename, "w", encoding="utf-8") as stream:
            json.dump({"clock": "sys", "clock_hz": self.clock_hz,
                       "depth": self.depth, "input_pipe_stages": self.input_pipe_stages,
                       "storage_qualifier": self.storage_qualifier,
                       "total_width": sum(len(p) for p in self.probes),
                       "probes": self.probe_map}, stream, indent=2)
            stream.write("\n")

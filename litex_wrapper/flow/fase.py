"""USER2 JTAG command transport and system-domain ILA diagnostics."""
from pathlib import Path
from migen import ClockSignal, Instance, Module, ResetSignal, Signal


class FaseJtag(Module):
    def __init__(self, cpu, platform, interface=None):
        f = cpu.fase if interface is None else interface
        params = dict(i_sys_clk=ClockSignal("sys"),
                      i_reset=ResetSignal("sys") | cpu.reset)
        for field in ("cmd_valid", "cmd_opcode", "cmd_index", "cmd_data", "cmd_pc", "rsp_ready"):
            params["o_" + field] = getattr(f, field)
        for field in ("cmd_ready", "rsp_valid", "rsp_error", "rsp_data"):
            params["i_" + field] = getattr(f, field)
        self.sources = []
        for name, width in (("flags", 8), ("received", 16), ("dispatched", 16),
                            ("responded", 16), ("tag", 16)):
            signal = Signal(width)
            params["o_debug_" + name] = signal
            self.sources.append(("fase_" + name, signal))
        self.sources += [("fase_" + name, getattr(f, name)) for name, _ in f.layout]
        self.sources += [("flight_" + name, signal) for name, signal in zip(
            ("cycle", "flags", "backend_target", "frontend_target", "if_req_va",
             "if_rsp_va", "trap_pc", "if_flags"), cpu.flight)]
        self.specials += Instance("FlowFaseJtag", name="fase_jtag", **params)
        platform.add_source(str(Path(__file__).parent / "rtl" / "FlowFaseJtag.sv"))

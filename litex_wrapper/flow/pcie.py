"""Single-hart XDMA endpoint. Bulk memory never traverses the CPU MMIO bus."""
from pathlib import Path

from migen import (Module, Signal, Record, Instance, ClockSignal, ResetSignal, ClockDomain,
                   If, FSM, NextState, NextValue)
from litex.soc.interconnect import wishbone


class FaseArbiter(Module):
    """Lock ownership before presenting a command, through response acceptance."""
    def __init__(self, target):
        self.jtag = Record(target.layout)
        self.pcie = Record(target.layout)
        owner = Signal()
        prefer_pcie = Signal()
        self.submodules.fsm = fsm = FSM(reset_state="IDLE")
        fsm.act("IDLE",
            If(self.jtag.cmd_valid | self.pcie.cmd_valid,
                NextValue(owner, self.pcie.cmd_valid & (~self.jtag.cmd_valid | prefer_pcie)),
                NextState("SEND")))
        for name in ("opcode", "index", "data", "pc"):
            self.comb += If(owner, getattr(target, "cmd_"+name).eq(getattr(self.pcie, "cmd_"+name))).Else(
                getattr(target, "cmd_"+name).eq(getattr(self.jtag, "cmd_"+name)))
        fsm.act("SEND",
            If(owner, target.cmd_valid.eq(self.pcie.cmd_valid), self.pcie.cmd_ready.eq(target.cmd_ready)).Else(
                target.cmd_valid.eq(self.jtag.cmd_valid), self.jtag.cmd_ready.eq(target.cmd_ready)),
            If(target.cmd_valid & target.cmd_ready, NextState("RESPONSE")))
        for source in (self.jtag, self.pcie):
            self.comb += [source.rsp_data.eq(target.rsp_data), source.rsp_error.eq(target.rsp_error)]
        fsm.act("RESPONSE",
            If(owner, self.pcie.rsp_valid.eq(target.rsp_valid), target.rsp_ready.eq(self.pcie.rsp_ready)).Else(
                self.jtag.rsp_valid.eq(target.rsp_valid), target.rsp_ready.eq(self.jtag.rsp_ready)),
            If(target.rsp_valid & target.rsp_ready,
                NextValue(prefer_pcie, ~owner), NextState("IDLE")))


def _axi_layout(lite=False):
    # Direction is relative to the AXI master.
    layout = {}
    for ch in ("aw", "ar"):
        layout[ch+"addr"] = (32 if lite else 64, "o")
        layout[ch+"prot"] = (3, "o")
        if not lite:
            for field, width in (("id",4),("len",8),("size",3),("burst",2),("lock",1),("cache",4)):
                layout[ch+field] = (width,"o")
    layout.update(wdata=(32 if lite else 256,"o"), wstrb=(4 if lite else 32,"o"),
                  bresp=(2,"i"), rresp=(2,"i"), rdata=(32 if lite else 256,"i"))
    if not lite:
        layout.update(wlast=(1,"o"), bid=(4,"i"), rid=(4,"i"), rlast=(1,"i"))
    for ch in ("aw","w","b","ar","r"):
        forward = ch in ("aw","w","ar")
        layout[ch+"valid"] = (1,"o" if forward else "i")
        layout[ch+"ready"] = (1,"i" if forward else "o")
    return layout


class BreezePcie(Module):
    def __init__(self, platform, fase, bridge_reset, cpu_reset=0):
        pads = platform.request("pcie_x8")
        platform.add_period_constraint(pads.clk_p, 10.0)
        self.user_clk = pcie_clk = Signal()
        self.user_reset_n = pcie_reset_n = Signal()
        refclk, refclk_gt = Signal(), Signal()
        self.link_up = Signal()
        self.bus = wishbone.Interface(data_width=64, address_width=32, addressing="word")
        memory_bus = wishbone.Interface(data_width=64, address_width=32, addressing="word")
        test_bus = wishbone.Interface(data_width=64, address_width=32, addressing="word")
        self.submodules.test_ram = wishbone.SRAM(4096, bus=test_bus)
        self.submodules.memory_decoder = wishbone.Decoder(memory_bus, [
            (lambda a: a[9:] == (0x40000000 >> 12), test_bus),
            (lambda a: a[28] == 1, self.bus),
        ])
        self.specials += Instance("IBUFDS_GTE3", i_CEB=0,
            i_I=pads.clk_p, i_IB=pads.clk_n, o_O=refclk_gt, o_ODIV2=refclk)
        # PERST/link reset resets the whole SoC: never abandon an accepted L2 request.
        # XDMA itself uses the independent slot reset/refclock, avoiding a reset loop.
        ip = dict(i_sys_clk=refclk, i_sys_clk_gt=refclk_gt, i_sys_rst_n=pads.rst_n,
            o_axi_aclk=pcie_clk, o_axi_aresetn=pcie_reset_n, o_user_lnk_up=self.link_up,
            i_pci_exp_rxp=pads.rx_p, i_pci_exp_rxn=pads.rx_n,
            o_pci_exp_txp=pads.tx_p, o_pci_exp_txn=pads.tx_n,
            i_usr_irq_req=0, i_cfg_mgmt_addr=0, i_cfg_mgmt_write=0,
            i_cfg_mgmt_write_data=0, i_cfg_mgmt_byte_enable=0, i_cfg_mgmt_read=0,
            i_cfg_mgmt_type1_cfg_reg_access=0)
        core_ports = []
        for lite, prefix, cdc_name in ((False,"m_axi_","flow_pcie_mm_cdc"),(True,"m_axil_","flow_pcie_ctl_cdc")):
            layout = _axi_layout(lite)
            system = {}
            cdc = dict(i_s_axi_aclk=pcie_clk, i_s_axi_aresetn=~bridge_reset,
                       i_m_axi_aclk=ClockSignal("sys"), i_m_axi_aresetn=~ResetSignal("sys"))
            if not lite:
                for ch in ("aw", "ar"):
                    cdc["i_s_axi_"+ch+"qos"] = 0
                    cdc["i_s_axi_"+ch+"region"] = 0
            for name, (width,direction) in layout.items():
                p, s = Signal(width), Signal(width)
                ip[direction+"_"+prefix+name] = p
                reverse = "i" if direction == "o" else "o"
                cdc[reverse+"_s_axi_"+name] = p
                cdc[direction+"_m_axi_"+name] = s
                if name.endswith(("prot","cache","lock")):
                    continue
                system[reverse+"_s_"+name] = s
            self.specials += Instance(cdc_name, **cdc)
            core_ports.append(system)
        self.specials += Instance("flow_xdma", name="flow_xdma_i", **ip)
        reads, writes, errors = Signal(32), Signal(32), Signal(32)
        mem = core_ports[0]
        mem.update(i_clk=ClockSignal(), i_reset=ResetSignal(),
            o_read_words=reads, o_write_words=writes, o_errors=errors)
        for name in ("cyc","stb","we","adr","dat_w","sel"):
            mem["o_wb_"+name] = getattr(memory_bus,name)
        for name in ("ack","err","dat_r"):
            mem["i_wb_"+name] = getattr(memory_bus,name)
        self.specials += Instance("FlowPcieMemory", **mem)
        ctl = core_ports[1]
        ctl.update(i_clk=ClockSignal(), i_reset=ResetSignal() | cpu_reset,
            i_read_words=reads, i_write_words=writes, i_memory_errors=errors)
        for field in ("cmd_valid","cmd_opcode","cmd_index","cmd_data","cmd_pc","rsp_ready"):
            ctl["o_"+field] = getattr(fase,field)
        for field in ("cmd_ready","rsp_valid","rsp_error","rsp_data"):
            ctl["i_"+field] = getattr(fase,field)
        self.specials += Instance("FlowPcieControl", **ctl)
        for filename in ("FlowPcieMemory.sv","FlowPcieControl.sv"):
            platform.add_source(str(Path(__file__).parent / "rtl" / filename))

"""Passive SD observability; no changes to the official controller datapath."""
from migen import Module, Signal, Cat, If


class SDObserver(Module):
    def __init__(self, sdcard):
        phy, core = sdcard.phy, sdcard.core
        self.sources = []
        self.state_maps = {}
        for name, module in [('core', core), ('init', phy.init),
                             ('cmdw', phy.cmdw), ('cmdr', phy.cmdr),
                             ('dataw', phy.dataw), ('datar', phy.datar)]:
            states = list(module.fsm.actions)
            self.state_maps['dbg_sd_' + name + '_state'] = states
            self.sources.append(('sd_' + name + '_state',
                                 Cat(*(module.fsm.ongoing(s) for s in states))))
        ticks = Signal(32)
        age = Signal(32)
        event = Signal()
        prev = Signal(8)
        status = Cat(core.cmd_event.status, core.data_event.status)
        self.sync += [ticks.eq(ticks + 1), prev.eq(status),
                      If(core.cmd_send.re, age.eq(0)).Elif(age != 0xffffffff, age.eq(age + 1))]
        # One periodic point per 1024 sys cycles also records quiet/timeout time.
        # Timestamps are essential: filtered VCD indices are NOT elapsed time.
        self.comb += event.eq(phy.sdpads.data_i_ce | core.cmd_send.re |
            (prev != status) | (ticks[:10] == 0) |
            (phy.cmdw.sink.valid & phy.cmdw.sink.ready) |
            (phy.cmdr.source.valid & phy.cmdr.source.ready))
        self.sources += [('sd_capture', event), ('sd_ticks', ticks), ('sd_cmd_age', age),
            ('sd_argument', core.cmd_argument.storage), ('sd_command', core.cmd_command.storage),
            ('sd_send', core.cmd_send.re), ('sd_response', core.cmd_response.status),
            ('sd_divider', phy.clocker.divider.storage), ('sd_clock_enable', phy.clocker.clk_en),
            ('sd_clock_stop', phy.clocker.stop), ('sd_clock_ce', phy.clocker.ce),
            ('sd_cmd_timeout_limit', phy.cmdr.timeout.storage),
            ('sd_data_timeout_limit', phy.datar.timeout.storage),
            ('sd_block_length', core.block_length.storage), ('sd_block_count', core.block_count.storage),
            ('sd_settings', phy.settings.storage), ('sd_cmdw_done', phy.cmdw.done)]
        for name, ep in [('cmd_tx', phy.cmdw.sink), ('cmd_rx', phy.cmdr.source),
                         ('data_rx', core.source), ('data_tx', core.sink)]:
            for field in ('valid', 'ready', 'data', 'first', 'last'):
                self.sources.append(('sd_' + name + '_' + field, getattr(ep, field)))
        self.sources.append(('sd_cmd_rx_status', phy.cmdr.source.status))

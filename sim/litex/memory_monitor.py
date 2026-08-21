"""Reusable passive memory-path monitors for Flow debug simulations."""

from migen import Display, If, Module, Signal

from flow.wiring import wishbone_byte_address


def _address_filter(address, start, end):
    condition = 1
    if start is not None:
        condition = condition & (address >= start)
    if end is not None:
        condition = condition & (address < end)
    return condition


class FlowWishboneMonitor(Module):
    """Observe one Wishbone interface without driving or backpressuring it."""

    def __init__(self, bus, name, max_events=1024,
                 address_start=None, address_end=None):
        if max_events <= 0:
            raise ValueError("Wishbone trace max_events must be positive")
        cycle = Signal(64)
        active = Signal()
        traced = Signal()
        started = Signal(64)
        events = Signal(max=max_events + 1)
        byte_address = Signal(64)
        latency = Signal(64)
        request = bus.cyc & bus.stb
        response = bus.ack | bus.err
        in_range = _address_filter(
            byte_address, address_start, address_end)
        should_trace = in_range & (events < max_events)

        self.comb += [
            byte_address.eq(wishbone_byte_address(bus.adr, bus.data_width)),
            latency.eq(cycle - started),
        ]
        self.sync += cycle.eq(cycle + 1)
        self.sync += [
            If(~active & request,
                active.eq(~response),
                traced.eq(should_trace & ~response),
                started.eq(cycle),
                If(should_trace,
                    Display(
                        f"[WB-REQ] name={name} cycle=%d addr=0x%x "
                        "adr=0x%x sel=0x%x we=%d dat_w=0x%x",
                        cycle, byte_address, bus.adr, bus.sel,
                        bus.we, bus.dat_w),
                    If(response,
                        Display(
                            f"[WB-RSP] name={name} cycle=%d ack=%d err=%d "
                            "dat_r=0x%x latency=0",
                            cycle, bus.ack, bus.err, bus.dat_r),
                        events.eq(events + 1)))
            ).Elif(active & response,
                active.eq(0),
                If(traced,
                    Display(
                        f"[WB-RSP] name={name} cycle=%d ack=%d err=%d "
                        "dat_r=0x%x latency=%d",
                        cycle, bus.ack, bus.err, bus.dat_r,
                        latency),
                    events.eq(events + 1)),
                traced.eq(0)
            )
        ]


class FlowDCacheRouteMonitor(Module):
    """Observe per-hart PMA/cache routing and the matching blocking response."""

    def __init__(self, traces, max_events=1024,
                 address_start=None, address_end=None):
        if max_events <= 0:
            raise ValueError("DCache trace max_events must be positive")
        cycle = Signal(64)
        self.sync += cycle.eq(cycle + 1)

        for hart, trace in enumerate(traces):
            sequence = Signal(64, name=f"dcache_trace_seq{hart}")
            active_sequence = Signal(64,
                name=f"dcache_trace_active_seq{hart}")
            events = Signal(max=max_events + 1,
                name=f"dcache_trace_events{hart}")
            traced = Signal(name=f"dcache_trace_active{hart}")
            in_range = _address_filter(
                trace.address, address_start, address_end)
            should_trace = in_range & (events < max_events)
            self.sync += [
                If(trace.request_valid,
                    active_sequence.eq(sequence),
                    sequence.eq(sequence + 1),
                    traced.eq(should_trace),
                    If(should_trace,
                        Display(
                            f"[DCACHE-REQ] hart={hart} cycle=%d seq=%d "
                            "addr=0x%x size_log2=%d we=%d wdata=0x%x "
                            "mask=0x%x allowed=%d cacheable=%d device=%d hit=%d",
                            cycle, sequence, trace.address, trace.size_log2,
                            trace.is_write, trace.write_data, trace.mask,
                            trace.pma_allowed, trace.pma_cacheable,
                            trace.pma_device, trace.cache_hit))
                ),
                If(trace.response_valid,
                    If(traced,
                        Display(
                            f"[DCACHE-RSP] hart={hart} cycle=%d seq=%d "
                            "data=0x%x error=%d",
                            cycle, active_sequence, trace.response_data,
                            trace.response_error),
                        events.eq(events + 1)),
                    traced.eq(0)
                )
            ]


class FlowRetireMemoryMonitor(Module):
    """Reuse Tandem retire records to log architecturally completed memory ops."""

    def __init__(self, retires, max_events=1024,
                 address_start=None, address_end=None):
        if max_events <= 0:
            raise ValueError("retire trace max_events must be positive")
        cycle = Signal(64)
        self.sync += cycle.eq(cycle + 1)
        for hart, retire in enumerate(retires):
            events = Signal(max=max_events + 1,
                name=f"retire_mem_events{hart}")
            in_range = _address_filter(
                retire.mem_addr, address_start, address_end)
            should_trace = (
                retire.valid & retire.mem_en & in_range &
                (events < max_events))
            self.sync += If(should_trace,
                Display(
                    f"[MEM-RETIRE] hart={hart} cycle=%d pc=0x%x inst=0x%x "
                    "addr=0x%x aligned=0x%x we=%d rdata=0x%x "
                    "wdata=0x%x mask=0x%x rd_we=%d rd=%d rd_data=0x%x",
                    cycle, retire.pc, retire.inst, retire.mem_addr,
                    retire.mem_aligned_addr, retire.mem_is_write,
                    retire.mem_rdata, retire.mem_wdata, retire.mem_wmask,
                    retire.rd_write_en, retire.rd_addr, retire.rd_data),
                events.eq(events + 1))


class FlowMemoryMonitor(Module):
    """Three-layer Flow memory observer: Tandem, DCache route, and Wishbone."""

    def __init__(self, cpu, wishbone_buses=(), max_events=1024,
                 address_start=None, address_end=None):
        self.submodules.retire = FlowRetireMemoryMonitor(
            cpu.retires, max_events=max_events,
            address_start=address_start, address_end=address_end)
        self.submodules.dcache = FlowDCacheRouteMonitor(
            cpu.dcache_traces, max_events=max_events,
            address_start=address_start, address_end=address_end)
        for index, (name, bus) in enumerate(wishbone_buses):
            setattr(self.submodules, f"wishbone_{index}", FlowWishboneMonitor(
                bus, name=name, max_events=max_events,
                address_start=address_start, address_end=address_end))

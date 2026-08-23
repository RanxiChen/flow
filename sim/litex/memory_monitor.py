"""Reusable passive memory-path monitors for Flow debug simulations."""

from migen import Cat, Display, If, Module, Signal

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


class FlowCompactEventMonitor(Module):
    """Unbounded compact event stream for whole-run offline analysis.

    This complements, rather than replaces, FlowMemoryMonitor. It has no event
    cap and emits only when an architectural or bus event occurs. The host-side
    runner removes these lines from the console and stores them chronologically.
    """

    PREFIX = "[FLOW-EVENT]"

    def __init__(self, cpu, wishbone_buses=()):
        cycle = Signal(64)
        self.sync += cycle.eq(cycle + 1)

        irq_width = 4 * len(cpu.retires)
        irq_vector = Signal(irq_width)
        previous_irq = Signal(irq_width)
        irq_initialized = Signal()
        self.comb += irq_vector.eq(Cat(cpu.msip, cpu.mtip, cpu.meip, cpu.seip))
        self.sync += If(~irq_initialized | (irq_vector != previous_irq),
            Display(
                f"{self.PREFIX} kind=I cycle=%0d msip=0x%0x mtip=0x%0x "
                "meip=0x%0x seip=0x%0x",
                cycle, cpu.msip, cpu.mtip, cpu.meip, cpu.seip),
            previous_irq.eq(irq_vector),
            irq_initialized.eq(1))

        for hart, retire in enumerate(cpu.retires):
            self.sync += If(retire.valid,
                Display(
                    f"{self.PREFIX} kind=R hart={hart} cycle=%0d pc=0x%0x "
                    "inst=0x%0x next=0x%0x rdwe=%0d rd=%0d rddata=0x%0x "
                    "memen=%0d memwe=%0d addr=0x%0x aligned=0x%0x "
                    "rdata=0x%0x wdata=0x%0x mask=0x%0x estop=%0d",
                    cycle, retire.pc, retire.inst, retire.next_pc,
                    retire.rd_write_en, retire.rd_addr, retire.rd_data,
                    retire.mem_en, retire.mem_is_write, retire.mem_addr,
                    retire.mem_aligned_addr, retire.mem_rdata,
                    retire.mem_wdata, retire.mem_wmask, retire.estop))

            dcache = cpu.dcache_traces[hart]
            sequence = Signal(64, name=f"compact_dcache_seq{hart}")
            active_sequence = Signal(64,
                name=f"compact_dcache_active_seq{hart}")
            self.sync += [
                If(dcache.request_valid,
                    active_sequence.eq(sequence),
                    sequence.eq(sequence + 1),
                    Display(
                        f"{self.PREFIX} kind=DQ hart={hart} cycle=%0d seq=%0d "
                        "addr=0x%0x size=%0d we=%0d wdata=0x%0x mask=0x%0x "
                        "allowed=%0d cacheable=%0d device=%0d hit=%0d",
                        cycle, sequence, dcache.address, dcache.size_log2,
                        dcache.is_write, dcache.write_data, dcache.mask,
                        dcache.pma_allowed, dcache.pma_cacheable,
                        dcache.pma_device, dcache.cache_hit)),
                If(dcache.response_valid,
                    Display(
                        f"{self.PREFIX} kind=DS hart={hart} cycle=%0d seq=%0d "
                        "data=0x%0x error=%0d",
                        cycle, active_sequence, dcache.response_data,
                        dcache.response_error))
            ]

        for bus_index, (_name, bus) in enumerate(wishbone_buses):
            active = Signal(name=f"compact_wb_active{bus_index}")
            sequence = Signal(64, name=f"compact_wb_seq{bus_index}")
            active_sequence = Signal(64,
                name=f"compact_wb_active_seq{bus_index}")
            byte_address = Signal(64,
                name=f"compact_wb_byte_address{bus_index}")
            request = bus.cyc & bus.stb
            response = bus.ack | bus.err
            self.comb += byte_address.eq(
                wishbone_byte_address(bus.adr, bus.data_width))
            self.sync += [
                If(~active & request,
                    active.eq(~response),
                    active_sequence.eq(sequence),
                    sequence.eq(sequence + 1),
                    Display(
                        f"{self.PREFIX} kind=WQ bus={bus_index} cycle=%0d "
                        "seq=%0d addr=0x%0x sel=0x%0x we=%0d data=0x%0x",
                        cycle, sequence, byte_address, bus.sel,
                        bus.we, bus.dat_w),
                    If(response,
                        Display(
                            f"{self.PREFIX} kind=WS bus={bus_index} cycle=%0d "
                            "seq=%0d ack=%0d err=%0d data=0x%0x",
                            cycle, sequence, bus.ack, bus.err, bus.dat_r))
                ).Elif(active & response,
                    active.eq(0),
                    Display(
                        f"{self.PREFIX} kind=WS bus={bus_index} cycle=%0d "
                        "seq=%0d ack=%0d err=%0d data=0x%0x",
                        cycle, active_sequence, bus.ack, bus.err, bus.dat_r))
            ]

        previous_fatal = Signal(len(cpu.retires))
        previous_estop = Signal(len(cpu.retires))
        self.sync += If((cpu.hart_fatal != previous_fatal) |
                (cpu.hart_estop != previous_estop),
            Display(
                f"{self.PREFIX} kind=F cycle=%0d fatal=0x%0x estop=0x%0x",
                cycle, cpu.hart_fatal, cpu.hart_estop),
            previous_fatal.eq(cpu.hart_fatal),
            previous_estop.eq(cpu.hart_estop))

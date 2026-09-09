"""Reusable passive memory-path monitors for Flow debug simulations."""

from migen import Array, Cat, Display, Finish, If, Module, Signal

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


class FlowCycleSnapshotMonitor(Module):
    """Emit a combined debug snapshot at a configurable cycle interval.

    The snapshot contains only passive, already exported CPU and SoC signals.
    It never drives ready/valid, interrupt, cache, or Wishbone state.
    """

    PREFIX = "[FLOW-CYCLE]"

    def __init__(self, cpu, wishbone_buses=(), interval=1):
        if interval <= 0:
            raise ValueError("cycle snapshot interval must be positive")

        cycle = Signal(64)
        emit_countdown = Signal(max=max(interval, 2), reset=0)
        retire_valid = Signal(len(cpu.retires))
        dcache_request = Signal(len(cpu.dcache_traces))
        dcache_response = Signal(len(cpu.dcache_traces))
        retire_counts = [
            Signal(64, name=f"cycle_retire_count{hart}")
            for hart in range(len(cpu.retires))
        ]
        last_pcs = [
            Signal(64, name=f"cycle_last_pc{hart}")
            for hart in range(len(cpu.retires))
        ]
        last_insts = [
            Signal(32, name=f"cycle_last_inst{hart}")
            for hart in range(len(cpu.retires))
        ]
        last_next_pcs = [
            Signal(64, name=f"cycle_last_next_pc{hart}")
            for hart in range(len(cpu.retires))
        ]

        self.comb += [
            retire_valid.eq(Cat(*[retire.valid for retire in cpu.retires])),
            dcache_request.eq(Cat(*[
                trace.request_valid for trace in cpu.dcache_traces
            ])),
            dcache_response.eq(Cat(*[
                trace.response_valid for trace in cpu.dcache_traces
            ])),
        ]

        line = (
            f"{self.PREFIX} cycle=%0d rv=0x%0x fatal=0x%0x estop=0x%0x "
            "msip=0x%0x mtip=0x%0x meip=0x%0x seip=0x%0x "
            "dq=0x%0x ds=0x%0x"
        )
        values = [
            cycle, retire_valid, cpu.hart_fatal, cpu.hart_estop,
            cpu.msip, cpu.mtip, cpu.meip, cpu.seip,
            dcache_request, dcache_response,
        ]
        for hart, retire in enumerate(cpu.retires):
            line += (
                f" h{hart}n=%0d h{hart}pc=0x%0x h{hart}i=0x%0x "
                f"h{hart}next=0x%0x h{hart}rpc=0x%0x h{hart}ri=0x%0x"
            )
            values.extend([
                retire_counts[hart], last_pcs[hart], last_insts[hart],
                last_next_pcs[hart], retire.pc, retire.inst,
            ])
        for index, (_name, bus) in enumerate(wishbone_buses):
            line += (
                f" wb{index}cyc=%0d wb{index}stb=%0d wb{index}ack=%0d "
                f"wb{index}err=%0d wb{index}we=%0d"
            )
            values.extend([bus.cyc, bus.stb, bus.ack, bus.err, bus.we])

        statements = [cycle.eq(cycle + 1)]
        if interval == 1:
            statements.append(Display(line, *values))
        else:
            statements.append(
                If(emit_countdown == 0,
                    Display(line, *values),
                    emit_countdown.eq(interval - 1),
                ).Else(
                    emit_countdown.eq(emit_countdown - 1),
                ))
        for hart, retire in enumerate(cpu.retires):
            statements.append(
                If(retire.valid,
                    retire_counts[hart].eq(retire_counts[hart] + 1),
                    last_pcs[hart].eq(retire.pc),
                    last_insts[hart].eq(retire.inst),
                    last_next_pcs[hart].eq(retire.next_pc)))
        self.sync += statements


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


class FlowFaultRetireMonitor(Module):
    """Keep recent retirements and dump them when a bad user control-flow appears.

    Normal execution is silent.  A trigger occurs when a retirement transfers
    control from a positive Sv39 address to the negative Sv39 half, or when its
    PC/next-PC equals ``trigger_pc``.  The latter lets a rerun target an exact
    address printed by a previous Linux Oops.  Simulation stops after the dump
    so the evidence cannot be buried by a subsequent panic loop.
    """

    PREFIX = "[FLOW-FAULT-RETIRE]"

    def __init__(self, cpu, depth=64, trigger_pc=None, stop_on_trigger=True):
        if depth <= 0:
            raise ValueError("Fault retire trace depth must be positive")
        if depth > 1024:
            raise ValueError("Fault retire trace depth must not exceed 1024")
        if trigger_pc is not None and not 0 <= trigger_pc < (1 << 64):
            raise ValueError("Fault retire trigger PC must fit in 64 bits")

        cycle = Signal(64)
        sequence = [Signal(64, name=f"fault_retire_seq{hart}")
                    for hart in range(len(cpu.retires))]
        write_indices = [Signal(max=depth, name=f"fault_retire_wr{hart}")
                         for hart in range(len(cpu.retires))]
        triggers = []
        histories = []

        self.sync += cycle.eq(cycle + 1)
        for hart, retire in enumerate(cpu.retires):
            valid = Array(Signal(name=f"fault_h{hart}_valid{slot}")
                          for slot in range(depth))
            event_cycle = Array(Signal(64, name=f"fault_h{hart}_cycle{slot}")
                                for slot in range(depth))
            event_sequence = Array(Signal(64, name=f"fault_h{hart}_seq{slot}")
                                   for slot in range(depth))
            pc = Array(Signal(64, name=f"fault_h{hart}_pc{slot}")
                       for slot in range(depth))
            inst = Array(Signal(32, name=f"fault_h{hart}_inst{slot}")
                         for slot in range(depth))
            next_pc = Array(Signal(64, name=f"fault_h{hart}_next{slot}")
                            for slot in range(depth))
            rd_write_en = Array(Signal(name=f"fault_h{hart}_rdwe{slot}")
                                for slot in range(depth))
            rd_addr = Array(Signal(5, name=f"fault_h{hart}_rd{slot}")
                            for slot in range(depth))
            rd_data = Array(Signal(64, name=f"fault_h{hart}_rddata{slot}")
                            for slot in range(depth))
            mem_en = Array(Signal(name=f"fault_h{hart}_memen{slot}")
                           for slot in range(depth))
            mem_is_write = Array(Signal(name=f"fault_h{hart}_memwe{slot}")
                                 for slot in range(depth))
            mem_addr = Array(Signal(64, name=f"fault_h{hart}_addr{slot}")
                             for slot in range(depth))
            mem_rdata = Array(Signal(64, name=f"fault_h{hart}_rdata{slot}")
                              for slot in range(depth))
            mem_wdata = Array(Signal(64, name=f"fault_h{hart}_wdata{slot}")
                              for slot in range(depth))
            mem_wmask = Array(Signal(8, name=f"fault_h{hart}_mask{slot}")
                              for slot in range(depth))
            histories.append((valid, event_cycle, event_sequence, pc, inst,
                              next_pc, rd_write_en, rd_addr, rd_data, mem_en,
                              mem_is_write, mem_addr, mem_rdata, mem_wdata,
                              mem_wmask))

            # Positive canonical Sv39 code must not branch directly into the
            # negative canonical half.  That transition is exactly what the
            # init failure's 0x0000003f... -> 0xffffffff... corruption does.
            user_to_negative = (
                (retire.pc[39:64] == 0) &
                (retire.next_pc[39:64] == ((1 << 25) - 1)))
            exact_match = 0
            if trigger_pc is not None:
                exact_match = ((retire.pc == trigger_pc) |
                               (retire.next_pc == trigger_pc))
            trigger = retire.valid & (user_to_negative | exact_match)
            triggers.append(trigger)

            index = write_indices[hart]
            self.sync += If(retire.valid,
                valid[index].eq(1),
                event_cycle[index].eq(cycle),
                event_sequence[index].eq(sequence[hart]),
                pc[index].eq(retire.pc),
                inst[index].eq(retire.inst),
                next_pc[index].eq(retire.next_pc),
                rd_write_en[index].eq(retire.rd_write_en),
                rd_addr[index].eq(retire.rd_addr),
                rd_data[index].eq(retire.rd_data),
                mem_en[index].eq(retire.mem_en),
                mem_is_write[index].eq(retire.mem_is_write),
                mem_addr[index].eq(retire.mem_addr),
                mem_rdata[index].eq(retire.mem_rdata),
                mem_wdata[index].eq(retire.mem_wdata),
                mem_wmask[index].eq(retire.mem_wmask),
                sequence[hart].eq(sequence[hart] + 1),
                If(index == depth - 1,
                    index.eq(0)
                ).Else(
                    index.eq(index + 1)))

        any_trigger = Signal()
        self.comb += any_trigger.eq(Cat(*triggers) != 0)
        triggered = Signal()
        dumps = []
        for hart, (retire, trigger, history) in enumerate(
                zip(cpu.retires, triggers, histories)):
            (valid, event_cycle, event_sequence, pc, inst, next_pc,
             rd_write_en, rd_addr, rd_data, mem_en, mem_is_write, mem_addr,
             mem_rdata, mem_wdata, mem_wmask) = history
            history_displays = [
                If(valid[slot],
                    Display(
                        f"{self.PREFIX} kind=H hart={hart} slot={slot} "
                        "seq=%0d cycle=%0d pc=0x%0x inst=0x%0x next=0x%0x "
                        "rdwe=%0d rd=%0d rddata=0x%0x memen=%0d memwe=%0d "
                        "addr=0x%0x rdata=0x%0x wdata=0x%0x mask=0x%0x",
                        event_sequence[slot], event_cycle[slot], pc[slot],
                        inst[slot], next_pc[slot], rd_write_en[slot],
                        rd_addr[slot], rd_data[slot], mem_en[slot],
                        mem_is_write[slot], mem_addr[slot], mem_rdata[slot],
                        mem_wdata[slot], mem_wmask[slot]))
                for slot in range(depth)
            ]
            dumps.append(If(trigger,
                Display(
                    f"{self.PREFIX} kind=T hart={hart} cycle=%0d seq=%0d "
                    "pc=0x%0x inst=0x%0x next=0x%0x rdwe=%0d rd=%0d "
                    "rddata=0x%0x memen=%0d memwe=%0d addr=0x%0x "
                    "rdata=0x%0x wdata=0x%0x mask=0x%0x",
                    cycle, sequence[hart], retire.pc, retire.inst,
                    retire.next_pc, retire.rd_write_en, retire.rd_addr,
                    retire.rd_data, retire.mem_en, retire.mem_is_write,
                    retire.mem_addr, retire.mem_rdata, retire.mem_wdata,
                    retire.mem_wmask),
                *history_displays))
        stop_statements = [Finish()] if stop_on_trigger else []
        self.sync += If(~triggered & any_trigger,
            *dumps,
            Display(f"{self.PREFIX} kind=STOP cycle=%0d", cycle),
            triggered.eq(1),
            *stop_statements)

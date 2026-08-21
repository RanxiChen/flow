"""Passive interrupt-chain diagnostics for Flow simulations."""

from migen import Cat, Display, If, Module, Signal


class FlowInterruptChainMonitor(Module):
    """Trace a LiteX event through a PLIC context to one CPU input.

    The monitor is deliberately observation-only.  It neither acknowledges an
    event nor drives the UART, PLIC, or CPU, so it can remain reusable across
    directed simulations without changing the behavior under test.
    """

    def __init__(self, uart, plic, cpu, retire, source_id=10, hart=0,
                 context=0):
        if source_id < 1 or source_id > len(plic.sources):
            raise ValueError("PLIC source_id is outside the source vector")
        if hart < 0 or hart >= len(plic.meip):
            raise ValueError("hart is outside the PLIC hart vector")
        if context < 0 or context >= len(plic.claims):
            raise ValueError("context is outside the PLIC context vector")

        cycle = Signal(64)
        retire_count = Signal(64)
        last_pc = Signal(64)
        last_inst = Signal(32)
        state = Signal(19)
        previous_state = Signal(19, reset=(1 << 19) - 1)

        uart_enable = uart.ev.enable.storage
        uart_status = uart.ev.status.status
        uart_pending = uart.ev.pending.status
        uart_irq = uart.ev.irq
        # Migen's Display special cannot serialize a raw _Slice and otherwise
        # emits its Python repr into Verilog.  Give every displayed slice a
        # named one-bit signal first.
        plic_source = Signal(name="irq_chain_plic_source")
        plic_pending = Signal(name="irq_chain_plic_pending")
        plic_claim = plic.claims[context]
        plic_claim_matches = Signal(name="irq_chain_plic_claim_matches")
        plic_meip = Signal(name="irq_chain_plic_meip")
        cpu_meip = Signal(name="irq_chain_cpu_meip")
        plic_priority = Signal(3, name="irq_chain_plic_priority")
        plic_enabled = Signal(name="irq_chain_plic_enabled")
        plic_threshold = Signal(3, name="irq_chain_plic_threshold")

        self.comb += [
            plic_source.eq(plic.sources[source_id - 1]),
            plic_pending.eq(plic.pending_bits[source_id]),
            plic_claim_matches.eq(plic_claim == source_id),
            plic_meip.eq(plic.meip[hart]),
            cpu_meip.eq(cpu.meip[hart]),
            plic_priority.eq(
                plic.debug_priorities[3*(source_id - 1):3*source_id]),
            plic_enabled.eq(plic.debug_enables[32*context + source_id]),
            plic_threshold.eq(
                plic.debug_thresholds[3*context:3*(context + 1)]),
            state.eq(Cat(
                uart_enable,
                uart_status,
                uart_pending,
                uart_irq,
                plic_source,
                plic_pending,
                plic_claim_matches,
                plic_meip,
                cpu_meip,
                plic_priority,
                plic_enabled,
                plic_threshold,
            )),
        ]
        self.sync += [
            cycle.eq(cycle + 1),
            If(retire.valid,
                retire_count.eq(retire_count + 1),
                last_pc.eq(retire.pc),
                last_inst.eq(retire.inst),
            ),
            If((state != previous_state) |
               (cycle == 2000) | (cycle == 50000) | (cycle == 150000),
                Display(
                    "[IRQ-CHAIN] cycle=%d uart_enable=0x%x "
                    "uart_status=0x%x uart_pending=0x%x uart_irq=%d "
                    "plic_source=%d plic_pending=%d claim=%d "
                    "priority=%d enabled=%d threshold=%d "
                    "plic_meip=%d cpu_meip=%d retires=%d "
                    "last_pc=0x%x last_inst=0x%x",
                    cycle, uart_enable, uart_status, uart_pending, uart_irq,
                    plic_source, plic_pending, plic_claim,
                    plic_priority, plic_enabled, plic_threshold,
                    plic_meip, cpu_meip, retire_count, last_pc, last_inst),
                previous_state.eq(state),
            ),
        ]

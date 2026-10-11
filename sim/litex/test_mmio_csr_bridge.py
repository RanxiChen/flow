#!/usr/bin/env python3
"""Exercise the production LiteX bridges with the KCU105 64/32-bit geometry.

Expectations are architectural CSR slot/value/strobe requirements. Known bugs
remain ordinary failing assertions; there are no xfails or adapted goldens.
Run on the configured simulation host, optionally with BE2_BRIDGE_EVIDENCE.
"""
import json
import os
import pathlib
import unittest

from migen import If, Module, Signal
from migen.sim import run_simulation
from litex.soc.cores.uart import UART
from litex.soc.interconnect import axi, csr, csr_bus, wishbone


UART_BASE = 0x12001000
VALUES = (0x52, 1, 1, 0, 0x11223344, 0x55667788)


def word_from_axi(address, data):
    """RV64 LW result, before sign extension; independent software arithmetic."""
    return (data // (2 ** (8 * (address % 8)))) & 0xffffffff


class CsrPath(Module):
    def __init__(self, native_uart=False, direct_wishbone=False):
        self.wb = wishbone.Interface(data_width=64, address_width=32, addressing='word')
        self.csrb = csr_bus.Interface(data_width=32, address_width=14, alignment=32)
        self.submodules.wb_to_csr = wishbone.Wishbone2CSR(
            self.wb, self.csrb, register=True)
        if not direct_wishbone:
            self.axil = axi.AXILiteInterface(data_width=64, address_width=32)
            self.submodules.axi_to_wb = axi.AXILite2Wishbone(self.axil, self.wb)
        if native_uart:
            # Register Buffer avoids an unrelated Memory simulation backend.
            self.submodules.uart = UART(tx_fifo_depth=1, rx_fifo_depth=1)
            registers = self.uart.get_csrs()
            self.register_names = [r.name for r in registers]
        else:
            registers = [csr.CSR(32, name=f'slot{i}') for i in range(len(VALUES))]
            self.read_counts = [Signal(32) for _ in registers]
            self.write_counts = [Signal(32) for _ in registers]
            self.written = [Signal(32) for _ in registers]
            for register, value, reads, writes, written in zip(
                    registers, VALUES, self.read_counts, self.write_counts, self.written):
                self.comb += register.w.eq(value)
                self.sync += [If(register.we, reads.eq(reads + 1)),
                              If(register.re, writes.eq(writes + 1), written.eq(register.r))]
        # Same CSR page and four-byte register stride as the generated KCU105.
        self.submodules.bank = csr_bus.CSRBank(
            registers, address=1, bus=self.csrb, paging=0x1000)


class AxiDriver:
    def __init__(self, bus, observations):
        self.bus = bus
        self.observations = observations

    def read(self, address, backpressure=0):
        b = self.bus
        yield b.r.ready.eq(0)
        yield b.ar.addr.eq(address)
        yield b.ar.valid.eq(1)
        yield
        for _ in range(128):
            if (yield b.ar.ready):
                break
            yield
        else:
            raise AssertionError(f'AR timeout at {address:#x}')
        yield
        yield b.ar.valid.eq(0)
        for _ in range(128):
            if (yield b.r.valid):
                break
            yield
        else:
            raise AssertionError(f'R timeout at {address:#x}')
        data, response = (yield b.r.data), (yield b.r.resp)
        for _ in range(backpressure):
            yield
            assert (yield b.r.valid) == 1, 'RVALID dropped under backpressure'
            assert (yield b.r.data) == data, 'RDATA changed under backpressure'
            assert (yield b.r.resp) == response, 'RRESP changed under backpressure'
        yield b.r.ready.eq(1)
        yield
        yield
        yield b.r.ready.eq(0)
        yield
        self.observations.append({'read_addr': hex(address), 'axi_data': hex(data),
                                  'lw_value': hex(word_from_axi(address, data)),
                                  'response': response})
        assert response == 0, f'AXI read error {response}'
        return word_from_axi(address, data)

    def write(self, address, value, separation=0, zero_strobes=False):
        # AW and W run concurrently, including W-before-AW. Positive separation
        # delays W; negative separation delays AW. Each channel holds payload.
        b = self.bus
        lane = address % 8
        yield b.aw.addr.eq(address)
        yield b.w.data.eq(value * (2 ** (8 * lane)))
        yield b.w.strb.eq(0 if zero_strobes else 0x0f * (2 ** lane))
        yield b.b.ready.eq(0)
        aw_done = w_done = False
        for cycle in range(128):
            aw_active = not aw_done and cycle >= max(0, -separation)
            w_active = not w_done and cycle >= max(0, separation)
            yield b.aw.valid.eq(aw_active)
            yield b.w.valid.eq(w_active)
            yield
            aw_accept = aw_active and (yield b.aw.ready)
            w_accept = w_active and (yield b.w.ready)
            # Keep accepted channel valid through the actual sampling edge.
            if aw_accept or w_accept:
                yield
            aw_done |= bool(aw_accept)
            w_done |= bool(w_accept)
            if aw_done and w_done:
                break
        else:
            raise AssertionError(f'AW/W timeout at {address:#x}')
        yield b.aw.valid.eq(0)
        yield b.w.valid.eq(0)
        for _ in range(128):
            if (yield b.b.valid):
                break
            yield
        else:
            raise AssertionError(f'B timeout at {address:#x}')
        response = (yield b.b.resp)
        yield b.b.ready.eq(1)
        yield
        yield
        yield b.b.ready.eq(0)
        yield
        self.observations.append({'write_addr': hex(address), 'value': hex(value),
                                  'separation': separation, 'response': response})
        assert response == 0, f'AXI write error {response}'


class MmioCsrBridgeTest(unittest.TestCase):
    def simulate(self, dut, process, observations):
        try:
            run_simulation(dut, process)
        finally:
            directory = os.environ.get('BE2_BRIDGE_EVIDENCE')
            if directory:
                path = pathlib.Path(directory)
                path.mkdir(parents=True, exist_ok=True)
                (path / (self._testMethodName + '.json')).write_text(
                    json.dumps(observations, indent=2) + '\n')

    def test_direct_wishbone_csr_both_lanes(self):
        dut, observations = CsrPath(direct_wishbone=True), []

        def process():
            for slot in (4, 5):
                address = UART_BASE + slot * 4
                yield dut.wb.adr.eq(address // 8)
                yield dut.wb.sel.eq(0xf0 if slot % 2 else 0x0f)
                yield dut.wb.cyc.eq(1)
                yield dut.wb.stb.eq(1)
                yield
                for _ in range(128):
                    if (yield dut.wb.ack):
                        break
                    yield
                else:
                    self.fail('Wishbone timeout')
                data = (yield dut.wb.dat_r)
                observations.append({'slot': slot, 'raw': hex(data)})
                self.assertEqual(word_from_axi(address, data), VALUES[slot])
                yield dut.wb.cyc.eq(0)
                yield dut.wb.stb.eq(0)
                yield
                yield
            self.assertEqual((yield dut.read_counts[4]), 1)
            self.assertEqual((yield dut.read_counts[5]), 1)
        self.simulate(dut, process(), observations)

    def test_axi_bridge_full_width_slave_both_lanes(self):
        dut, observations = Module(), []
        bus = axi.AXILiteInterface(data_width=64, address_width=32)
        wb = wishbone.Interface(data_width=64, address_width=32, addressing='word')
        dut.submodules.bridge = axi.AXILite2Wishbone(bus, wb)
        dut.comb += [wb.ack.eq(wb.cyc & wb.stb),
                     wb.dat_r.eq(VALUES[4] + VALUES[5] * (2 ** 32))]
        driver = AxiDriver(bus, observations)

        def process():
            for slot in (4, 5):
                actual = yield from driver.read(UART_BASE + slot * 4, backpressure=5)
                self.assertEqual(actual, VALUES[slot])
        self.simulate(dut, process(), observations)

    def check_slot_read(self, slot, backpressure=0, side_effect_only=False):
        dut, observations = CsrPath(), []
        driver = AxiDriver(dut.axil, observations)

        def process():
            actual = yield from driver.read(UART_BASE + slot * 4, backpressure)
            counts = []
            for counter in dut.read_counts:
                counts.append((yield counter))
            observations.append({'read_strobes_by_slot': counts})
            if side_effect_only:
                expected = [int(i == slot) for i in range(len(VALUES))]
                self.assertEqual(counts, expected, 'read touched a different CSR or repeated its side effect')
            else:
                self.assertEqual(actual, VALUES[slot], f'CSR slot{slot} value/lane mismatch')
        self.simulate(dut, process(), observations)

    def test_composed_lower_csr_read(self):
        self.check_slot_read(4)

    def test_composed_upper_csr_read(self):
        self.check_slot_read(5)

    def test_composed_upper_read_targets_only_requested_csr(self):
        self.check_slot_read(5, side_effect_only=True)

    def test_composed_upper_read_with_r_backpressure(self):
        self.check_slot_read(5, backpressure=7)

    def test_composed_lower_read_backpressure_no_duplicate_side_effect(self):
        self.check_slot_read(4, backpressure=7, side_effect_only=True)

    def test_composed_writes_both_lanes_and_independent_aw_w(self):
        dut, observations = CsrPath(), []
        driver = AxiDriver(dut.axil, observations)

        def process():
            for separation in (0, 4, -4):
                for slot in (4, 5):
                    value = 0xa000 + slot + separation
                    yield from driver.write(UART_BASE + slot * 4, value, separation)
                    self.assertEqual((yield dut.written[slot]), value)
            self.assertEqual((yield dut.write_counts[4]), 3)
            self.assertEqual((yield dut.write_counts[5]), 3)
        self.simulate(dut, process(), observations)

    def test_composed_zero_write_strobes_have_no_csr_side_effect(self):
        dut, observations = CsrPath(), []
        driver = AxiDriver(dut.axil, observations)

        def process():
            yield from driver.write(UART_BASE + 20, 3, zero_strobes=True)
            for counter in dut.write_counts:
                self.assertEqual((yield counter), 0)
        self.simulate(dut, process(), observations)

    def check_native_uart(self, handler=False):
        dut, observations = CsrPath(native_uart=True), []
        driver = AxiDriver(dut.axil, observations)

        def process():
            self.assertEqual(dut.register_names[:6],
                             ['rxtx', 'txfull', 'rxempty', 'ev_status', 'ev_pending', 'ev_enable'])
            yield dut.uart.source.ready.eq(1)
            yield from driver.write(UART_BASE + 20, 3)
            yield dut.uart.sink.data.eq(ord('R'))
            yield dut.uart.sink.valid.eq(1)
            yield
            yield
            yield dut.uart.sink.valid.eq(0)
            yield
            self.assertEqual((yield dut.uart.ev.enable.storage), 3, 'fixture enable write did not reach UART')
            self.assertEqual((yield dut.uart.ev.irq), 1, 'fixture did not reach an active IRQ')
            if not handler:
                pending = yield from driver.read(UART_BASE + 16)
                enabled = yield from driver.read(UART_BASE + 20)
                self.assertEqual(pending, 3)
                self.assertEqual(enabled, 3, 'native UART enable read differs from its stored value')
                return
            # BIOS behavior with initially empty TX ring and available RX room.
            # Three service attempts are enough for the one-byte finite fixture.
            for attempt in range(3):
                pending = yield from driver.read(UART_BASE + 16)
                enabled = yield from driver.read(UART_BASE + 20)
                stat = pending & enabled
                if stat & 2:
                    for _ in range(4):
                        if (yield from driver.read(UART_BASE + 8)):
                            break
                        byte = yield from driver.read(UART_BASE)
                        observations.append({'received_byte': byte})
                        yield from driver.write(UART_BASE + 16, 2)
                    else:
                        self.fail('one-byte RX fixture failed to drain')
                if stat & 1:
                    yield from driver.write(UART_BASE + 20, 2)
                observations.append({'attempt': attempt, 'stat': stat,
                                      'uart_irq': (yield dut.uart.ev.irq)})
            self.assertEqual((yield dut.uart.ev.irq), 0, 'BIOS UART service makes no progress')
        self.simulate(dut, process(), observations)

    def test_native_uart_pending_and_enable_readback(self):
        self.check_native_uart()

    def test_native_uart_bios_service_clears_irq(self):
        self.check_native_uart(handler=True)

    def test_native_uart_txfull_read_does_not_pop_rx_fifo(self):
        dut, observations = CsrPath(native_uart=True), []
        driver = AxiDriver(dut.axil, observations)

        def process():
            yield dut.uart.sink.data.eq(ord('R'))
            yield dut.uart.sink.valid.eq(1)
            yield
            yield
            yield dut.uart.sink.valid.eq(0)
            yield
            self.assertEqual((yield dut.uart._rxempty.status), 0)
            actual = yield from driver.read(UART_BASE + 4)
            observations.append({'txfull_read': actual,
                                  'rxempty_after_status_read': (yield dut.uart._rxempty.status)})
            self.assertEqual(actual, 0, 'empty TX queue must report txfull=0')
            self.assertEqual((yield dut.uart._rxempty.status), 0,
                             'reading TXFULL must not consume the queued RX byte')
            byte = yield from driver.read(UART_BASE)
            self.assertEqual(byte, ord('R'))
        self.simulate(dut, process(), observations)

    def test_native_uart_full_tx_fifo_status_reads_one(self):
        dut, observations = CsrPath(native_uart=True), []
        driver = AxiDriver(dut.axil, observations)

        def process():
            yield dut.uart.source.ready.eq(0)
            yield from driver.write(UART_BASE, ord('Q'))
            self.assertEqual((yield dut.uart._txfull.status), 1,
                             'fixture did not fill TX queue')
            self.assertEqual((yield dut.uart.source.valid), 1)
            self.assertEqual((yield dut.uart.source.data), ord('Q'))
            actual = yield from driver.read(UART_BASE + 4)
            observations.append({'stored_txfull': (yield dut.uart._txfull.status),
                                  'txfull_read': actual})
            self.assertEqual(actual, 1, 'full UART TX queue was reported as empty')
        self.simulate(dut, process(), observations)


if __name__ == '__main__':
    unittest.main(verbosity=2)

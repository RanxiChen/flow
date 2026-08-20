"""Minimal 16550-compatible UART register block for xv6/Linux simulation."""

from migen import Array, Cat, If, Module, Mux, Signal
from litex.soc.interconnect import stream, wishbone


class BreezeUart16550(Module):
    def __init__(self):
        self.bus = bus = wishbone.Interface(
            data_width=64, address_width=32, addressing="word")
        self.tx = stream.Endpoint([("data", 8)])
        self.rx = stream.Endpoint([("data", 8)])
        self.interrupt = Signal()

        ier = Signal(4)
        lcr = Signal(8, reset=0x03)
        dll = Signal(8, reset=1)
        dlm = Signal(8)
        dlab = lcr[7]
        lane = Signal(3)
        lane_found = Signal()
        # Lowest selected byte lane is the addressed 16550 byte register.
        self.comb += [lane.eq(0), lane_found.eq(0)]
        for n in reversed(range(8)):
            self.comb += If(bus.sel[n], lane.eq(n), lane_found.eq(1))
        reg_offset = Signal(3)
        self.comb += reg_offset.eq(lane)
        write_bytes = Array(bus.dat_w[8*n:8*(n+1)] for n in range(8))
        write_data = write_bytes[lane]

        request = bus.cyc & bus.stb & lane_found
        tx_access = request & bus.we & (reg_offset == 0) & ~dlab
        rx_access = request & ~bus.we & (reg_offset == 0) & ~dlab
        can_respond = Mux(tx_access, self.tx.ready, Mux(rx_access, self.rx.valid, 1))
        responding = Signal(reset=0)
        read_byte = Signal(8)
        read_word = Signal(64)
        self.comb += [
            self.tx.valid.eq(tx_access & ~responding),
            self.tx.data.eq(write_data),
            self.rx.ready.eq(rx_access & ~responding & can_respond),
            bus.ack.eq(responding & bus.cyc & bus.stb),
            bus.err.eq(0),
            read_byte.eq(0),
            read_word.eq(read_byte << (lane << 3)),
            bus.dat_r.eq(read_word),
            self.interrupt.eq((ier[0] & self.rx.valid) | (ier[1] & self.tx.ready)),
        ]
        self.comb += If(reg_offset == 0,
                        read_byte.eq(Mux(dlab, dll, self.rx.data))) \
            .Elif(reg_offset == 1, read_byte.eq(Mux(dlab, dlm, ier))) \
            .Elif(reg_offset == 2,
                  read_byte.eq(Mux(ier[0] & self.rx.valid, 0x04,
                               Mux(ier[1] & self.tx.ready, 0x02, 0x01)))) \
            .Elif(reg_offset == 3, read_byte.eq(lcr)) \
            .Elif(reg_offset == 5,
                  read_byte.eq(self.rx.valid | (self.tx.ready << 5) | (self.tx.ready << 6)))

        self.sync += [
            If(request & ~responding & can_respond,
               responding.eq(1),
               If(bus.we,
                  If(reg_offset == 0,
                     If(dlab, dll.eq(write_data)))
                  .Elif(reg_offset == 1,
                        If(dlab, dlm.eq(write_data)).Else(ier.eq(write_data[:4])))
                  .Elif(reg_offset == 3, lcr.eq(write_data))))
            .Elif(~bus.cyc | ~bus.stb, responding.eq(0))
        ]

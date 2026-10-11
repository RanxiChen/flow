"""Preserve the addressed 32-bit CSR slot through a 64-bit MMIO bridge.

LiteX keeps AR unaccepted until Wishbone ACK, so the AXI master holds the
original byte address throughout the Wishbone read. No pipeline/FSM/buffer
is added. Non-CSR reads retain full-word selection.
"""
from migen import If, Module, Mux
from litex.soc.interconnect import axi, wishbone


CSR_DATA_WIDTH = 32
CSR_ADDRESS_WIDTH = 14
CSR_PAGING = 0x1000
CSR_WINDOW_SIZE = 2 ** (CSR_ADDRESS_WIDTH + 2)


class BreezeMmioBridge(Module):
    def __init__(self, axil, wb, csr_origin, csr_size=CSR_WINDOW_SIZE):
        if axil.data_width != 64 or wb.data_width != 64 or wb.addressing != 'word':
            raise ValueError('Breeze MMIO requires 64-bit AXI-Lite and word-addressed Wishbone')
        if csr_origin % 8 or csr_size < 8 or csr_size % 8:
            raise ValueError('CSR window must contain aligned 64-bit words')
        if csr_origin < 0 or csr_origin + csr_size > 2 ** axil.address_width:
            raise ValueError('CSR window does not fit the AXI-Lite address space')
        self.csr_origin, self.csr_size = int(csr_origin), int(csr_size)
        internal = wishbone.Interface(data_width=64, address_width=axil.address_width,
                                      addressing='word')
        self.submodules.protocol = axi.AXILite2Wishbone(axil, internal)
        self.comb += internal.connect(wb, omit={'sel'})
        csr_read = ((axil.ar.addr >= self.csr_origin) &
                    (axil.ar.addr < self.csr_origin + self.csr_size))
        self.comb += [
            wb.sel.eq(internal.sel),
            If(internal.cyc & internal.stb & ~internal.we & csr_read,
               wb.sel.eq(Mux(axil.ar.addr[2], 0xf0, 0x0f)))
        ]

    def check_csr_region(self, region, data_width, alignment):
        actual = (int(region.origin), int(region.size), int(data_width), int(alignment))
        expected = (self.csr_origin, self.csr_size, CSR_DATA_WIDTH, 32)
        if actual != expected:
            raise ValueError(f'CSR/MMIO bridge contract mismatch: actual={actual}, expected={expected}')

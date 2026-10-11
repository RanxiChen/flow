#!/usr/bin/env python3
"""Frozen v1 AXI cluster KCU105 bring-up target (no board programming)."""
import argparse
import os
import sys

from litex.soc.cores.cpu import CPUS
from litex.soc.integration.soc import SoCRegion
from litex.soc.integration.soc_core import SoCCore
from litex_boards.platforms import xilinx_kcu105
from litex_boards.targets.xilinx_kcu105 import _CRG
from litedram.modules import EDY4016A
from litedram.phy import usddrphy
from migen import Cat

FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
sys.path.insert(0, os.path.join(FLOW_ROOT, 'litex_wrapper'))
from flow import Breeze, BreezeTiny
from flow.core import BreezeTinyDebug, BreezeDma, BreezeTinyDma, BreezeTinyDebugDma, BreezeRvvDma
from flow.axi_router import check_soc_regions, platform_regions, add_inactive_boot_rom
from flow.clint_verilog import BreezeClintVerilog
from flow.plic_verilog import BreezePlicVerilog
from flow.wiring import pack_plic_sources
from flow.mmio_bridge import CSR_ADDRESS_WIDTH, CSR_DATA_WIDTH, CSR_PAGING
from build_support import SnapshotBuilder

SYS_CLK_FREQ = 100_000_000
UART_BAUDRATE = 115_200
REGIONS = platform_regions(FLOW_ROOT)
ROM_SIZE = REGIONS['linux_boot_rom']['size']
SRAM_SIZE = REGIONS['sram']['size']
DDR_SIZE = REGIONS['main_ram']['size']
CLINT_ORIGIN, CLINT_SIZE = REGIONS['machine_timer']['origin'], REGIONS['machine_timer']['size']
PLIC_ORIGIN, PLIC_SIZE = REGIONS['plic']['origin'], REGIONS['plic']['size']
MTIME_FREQ = 1_000_000
CLINT_MSIP_OFFSET, CLINT_MTIMECMP_OFFSET, CLINT_MTIME_OFFSET = 0, 0x4000, 0xbff8
PLIC_NUM_SOURCES, UART_PLIC_SOURCE = 31, 10
BUILD_DIR = os.path.join(FLOW_ROOT, 'build/fpga/kcu105-breeze-axi')
TINY_BUILD_DIR = os.path.join(FLOW_ROOT, 'build/fpga/kcu105-breeze-tiny-axi')
DEBUG_BUILD_DIR = TINY_BUILD_DIR + '-debug'
CPUS['breeze'] = Breeze
CPUS['breeze_tiny'] = BreezeTiny
CPUS['breeze_tiny_debug'] = BreezeTinyDebug
CPUS['breeze_dma'] = BreezeDma
CPUS['breeze_tiny_dma'] = BreezeTinyDma
CPUS['breeze_tiny_debug_dma'] = BreezeTinyDebugDma
CPUS['breeze_rvv_dma'] = BreezeRvvDma


class BreezeKCU105SoC(SoCCore):
    csr_map = {'ctrl': 0, 'uart': 1, 'timer0': 2, 'ddrphy': 3, 'identifier_mem': 4, 'sdram': 5,
               'sdcard': 6}
    irq_map = {'uart': UART_PLIC_SOURCE, 'sdcard': UART_PLIC_SOURCE + 1}

    def __init__(self, cpu_type='breeze', debug=False, sys_clk_freq=SYS_CLK_FREQ, with_sdcard=False):
        if cpu_type not in ('breeze', 'breeze-tiny', 'breeze-rvv'):
            raise ValueError(f'Unsupported KCU105 CPU: {cpu_type}')
        if debug and cpu_type != 'breeze-tiny':
            raise ValueError('--debug only supports --cpu-type breeze-tiny (single hart)')
        if sys_clk_freq != SYS_CLK_FREQ:
            raise ValueError('v1 bring-up uses 100 MHz')
        platform = xilinx_kcu105.Platform()
        self.crg = _CRG(platform, sys_clk_freq)
        cpu_key = 'breeze_tiny_debug' if debug else cpu_type.replace('-', '_')
        if with_sdcard:
            cpu_key += '_dma'
        if cpu_type == 'breeze-rvv' and not with_sdcard:
            raise ValueError('the RVV product includes SD hardware')
        super().__init__(platform, clk_freq=sys_clk_freq,
            ident='Breeze v1 AXI DDR4 SoC on KCU105',
            cpu_type=cpu_key,
            cpu_variant='standard', bus_standard='wishbone', bus_data_width=64,
            bus_address_width=32, bus_bursting=False, bus_interconnect='shared', bus_timeout=None,
            integrated_rom_size=ROM_SIZE, integrated_sram_size=SRAM_SIZE,
            integrated_main_ram_size=0, csr_data_width=CSR_DATA_WIDTH,
            csr_address_width=CSR_ADDRESS_WIDTH,
            csr_paging=CSR_PAGING, with_ctrl=True, with_uart=True, uart_name='serial',
            uart_baudrate=UART_BAUDRATE, with_timer=True)
        self.ddrphy = usddrphy.USDDRPHY(platform.request('ddram'), memtype='DDR4',
            sys_clk_freq=sys_clk_freq, iodelay_clk_freq=200e6)
        self.add_sdram(name='sdram', phy=self.ddrphy, module=EDY4016A(sys_clk_freq, '1:4'),
            size=DDR_SIZE, l2_cache_size=0)
        add_inactive_boot_rom(self, FLOW_ROOT)
        check_soc_regions(self, FLOW_ROOT)
        self.clint = BreezeClintVerilog(platform=platform, sys_clk_freq=sys_clk_freq,
            timebase_freq=MTIME_FREQ, num_harts=self.cpu.num_harts, region_size=CLINT_SIZE,
            msip_offset=CLINT_MSIP_OFFSET, mtimecmp_offset=CLINT_MTIMECMP_OFFSET,
            mtime_offset=CLINT_MTIME_OFFSET)
        self.bus.add_slave(name='clint', slave=self.clint.bus,
            region=SoCRegion(origin=CLINT_ORIGIN, size=CLINT_SIZE, cached=False))
        self.comb += [self.cpu.msip.eq(self.clint.msip), self.cpu.mtip.eq(self.clint.mtip),
                      self.cpu.time.eq(self.clint.mtime)]
        self.plic = BreezePlicVerilog(platform=platform, num_harts=self.cpu.num_harts,
                                     num_sources=PLIC_NUM_SOURCES)
        self.bus.add_slave(name='plic', slave=self.plic.bus,
            region=SoCRegion(origin=PLIC_ORIGIN, size=PLIC_SIZE, cached=False))
        if with_sdcard:
            # add_cpu created a dedicated SoCDMABusHandler from cpu.dma_bus.
            # LiteSDCard's two DMA masters bind to it, never the main bus.
            if not hasattr(self, 'dma_bus'):
                raise RuntimeError('SD requires the coherent CPU DMA bus')
            self.add_sdcard(mode='read+write')
            self.add_constant('BREEZE_SDCARD_PLIC_SOURCE', UART_PLIC_SOURCE + 1)
        interrupt_sources = Cat(self.uart.ev.irq, self.sdcard.ev.irq) if with_sdcard else self.uart.ev.irq
        self.comb += [self.plic.sources.eq(pack_plic_sources(interrupt_sources,
            first_source=UART_PLIC_SOURCE, num_sources=PLIC_NUM_SOURCES)),
            self.cpu.meip.eq(self.plic.meip), self.cpu.seip.eq(self.plic.seip)]
        for name, value in {'BREEZE_NUM_HARTS': self.cpu.num_harts, 'BREEZE_CLINT': CLINT_ORIGIN,
            'BREEZE_MSIP': CLINT_ORIGIN + CLINT_MSIP_OFFSET,
            'BREEZE_MTIMECMP': CLINT_ORIGIN + CLINT_MTIMECMP_OFFSET,
            'BREEZE_MTIME': CLINT_ORIGIN + CLINT_MTIME_OFFSET, 'BREEZE_MTIME_FREQUENCY': MTIME_FREQ,
            'BREEZE_PLIC': PLIC_ORIGIN, 'BREEZE_UART_PLIC_SOURCE': UART_PLIC_SOURCE}.items():
            self.add_constant(name, value)
        if debug:
            from flow.ila import BreezeDebugILA
            self.debug_ila = BreezeDebugILA(self.cpu, platform, clock_hz=sys_clk_freq,
                extra_sources=(("pll_locked", self.crg.pll.locked),))
            self.comb += platform.request('user_led', 0).eq(self.cpu.hang)

    def do_finalize(self):
        super().do_finalize()
        self.cpu.mmio_bridge.check_csr_region(
            self.bus.regions['csr'], self.csr.data_width, self.csr.alignment)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cpu-type', choices=('breeze', 'breeze-tiny', 'breeze-rvv'), default='breeze-tiny')
    parser.add_argument('--sys-clk-freq', type=int, choices=(SYS_CLK_FREQ,), default=SYS_CLK_FREQ)
    parser.add_argument('--debug', action='store_true')
    parser.add_argument('--with-sdcard', action='store_true',
                        help='native SD, coherent DMA, BIOS SD software-image boot')
    parser.add_argument('--output-dir')
    parser.add_argument('--build', action='store_true', help='run Vivado after generating BIOS and gateware')
    parser.add_argument('--vivado-threads', type=int, choices=(2, 4), default=4)
    args = parser.parse_args()
    if args.debug and args.cpu_type != 'breeze-tiny':
        parser.error('--debug requires --cpu-type breeze-tiny')
    soc = BreezeKCU105SoC(args.cpu_type, args.debug, args.sys_clk_freq, args.with_sdcard)
    output = args.output_dir or (DEBUG_BUILD_DIR if args.debug else
                               TINY_BUILD_DIR if args.cpu_type == 'breeze-tiny' else BUILD_DIR)
    builder = SnapshotBuilder(soc, output_dir=output, csr_csv=os.path.join(output, 'csr.csv'),
        csr_json=os.path.join(output, 'csr.json'), integrated_rom_auto_size=False, bios_console='lite')
    soc.platform.toolchain.pre_synthesis_commands.append(
        f'set_param general.maxThreads {args.vivado_threads}')
    # A generated file is publishable only after complete routed timing passes.
    # This check runs before write_bitstream, without weakening any constraint.
    soc.platform.toolchain.bitstream_commands += [
        'report_timing_summary -delay_type min_max -report_unconstrained -file routed-generation-gate.rpt',
        'source routed_timing_gate.tcl',
        'flow_require_routed_timing routed-generation-gate.rpt',
        'set setup_path [get_timing_paths -quiet -delay_type max -max_paths 1]',
        'set hold_path [get_timing_paths -quiet -delay_type min -max_paths 1]',
        'if {{![llength $setup_path] || ![llength $hold_path]}} {{ error "Missing routed timing paths" }}',
        'if {{[get_property SLACK $setup_path] < 0 || [get_property SLACK $hold_path] < 0}} {{ error "Routed setup/hold failed; bitstream withheld" }}',
    ]
    os.makedirs(output, exist_ok=True)
    if args.debug:
        soc.debug_ila.write_probe_map(os.path.join(output, 'ila-probes.json'))
    soc.platform.toolchain.additional_commands += [
        'report_timing_summary -file soc-timing-summary.rpt',
        'report_timing -max_paths 10 -path_type full -file soc-worst-10.rpt',
        'report_utilization -file soc-utilization.rpt',
    ]
    builder.build(run=args.build, vivado_synth_directive='default',
        vivado_opt_directive='default', vivado_place_directive='default',
        vivado_post_place_phys_opt_directive='default', vivado_route_directive='default')


if __name__ == '__main__':
    main()

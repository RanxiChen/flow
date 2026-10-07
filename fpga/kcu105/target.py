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

FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
sys.path.insert(0, os.path.join(FLOW_ROOT, 'litex_wrapper'))
from flow import Breeze, BreezeTiny
from flow.core import BreezeTinyDebug
from flow.axi_router import check_soc_regions, platform_regions
from flow.clint_verilog import BreezeClintVerilog
from flow.plic_verilog import BreezePlicVerilog
from flow.wiring import pack_plic_sources
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


class BreezeKCU105SoC(SoCCore):
    csr_map = {'ctrl': 0, 'uart': 1, 'timer0': 2, 'ddrphy': 3, 'identifier_mem': 4, 'sdram': 5}
    irq_map = {'uart': UART_PLIC_SOURCE}

    def __init__(self, cpu_type='breeze', debug=False, sys_clk_freq=SYS_CLK_FREQ):
        if cpu_type not in ('breeze', 'breeze-tiny'):
            raise ValueError(f'Unsupported KCU105 CPU: {cpu_type}')
        if debug and cpu_type != 'breeze-tiny':
            raise ValueError('--debug only supports --cpu-type breeze-tiny (single hart)')
        if sys_clk_freq != SYS_CLK_FREQ:
            raise ValueError('v1 bring-up uses 100 MHz')
        platform = xilinx_kcu105.Platform()
        self.crg = _CRG(platform, sys_clk_freq)
        super().__init__(platform, clk_freq=sys_clk_freq,
            ident='Breeze v1 AXI DDR4 SoC on KCU105',
            cpu_type='breeze_tiny_debug' if debug else cpu_type.replace('-', '_'),
            cpu_variant='standard', bus_standard='wishbone', bus_data_width=64,
            bus_address_width=32, bus_bursting=False, bus_interconnect='shared', bus_timeout=None,
            integrated_rom_size=ROM_SIZE, integrated_sram_size=SRAM_SIZE,
            integrated_main_ram_size=0, csr_data_width=32, csr_address_width=14,
            csr_paging=0x1000, with_ctrl=True, with_uart=True, uart_name='serial',
            uart_baudrate=UART_BAUDRATE, with_timer=True)
        self.ddrphy = usddrphy.USDDRPHY(platform.request('ddram'), memtype='DDR4',
            sys_clk_freq=sys_clk_freq, iodelay_clk_freq=200e6)
        self.add_sdram(name='sdram', phy=self.ddrphy, module=EDY4016A(sys_clk_freq, '1:4'),
            size=DDR_SIZE, l2_cache_size=0)
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
        self.comb += [self.plic.sources.eq(pack_plic_sources(self.uart.ev.irq,
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
            self.debug_ila = BreezeDebugILA(self.cpu, platform, clock_hz=sys_clk_freq)
            self.comb += platform.request('user_led', 0).eq(self.cpu.hang)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cpu-type', choices=('breeze', 'breeze-tiny'), default='breeze-tiny')
    parser.add_argument('--sys-clk-freq', type=int, choices=(SYS_CLK_FREQ,), default=SYS_CLK_FREQ)
    parser.add_argument('--debug', action='store_true')
    parser.add_argument('--output-dir')
    parser.add_argument('--build', action='store_true', help='run Vivado after generating BIOS and gateware')
    args = parser.parse_args()
    if args.debug and args.cpu_type != 'breeze-tiny':
        parser.error('--debug requires --cpu-type breeze-tiny')
    soc = BreezeKCU105SoC(args.cpu_type, args.debug, args.sys_clk_freq)
    output = args.output_dir or (DEBUG_BUILD_DIR if args.debug else
                               TINY_BUILD_DIR if args.cpu_type == 'breeze-tiny' else BUILD_DIR)
    builder = SnapshotBuilder(soc, output_dir=output, csr_csv=os.path.join(output, 'csr.csv'),
        csr_json=os.path.join(output, 'csr.json'), integrated_rom_auto_size=False, bios_console='lite')
    os.makedirs(output, exist_ok=True)
    if args.debug:
        soc.debug_ila.write_probe_map(os.path.join(output, 'ila-probes.json'))
    soc.platform.toolchain.additional_commands += [
        'report_timing_summary -file soc-timing-summary.rpt',
        'report_timing -max_paths 10 -path_type full -file soc-worst-10.rpt',
        'report_utilization -file soc-utilization.rpt',
    ]
    builder.build(run=args.build, vivado_synth_directive='AreaOptimized_high',
        vivado_opt_directive='ExploreArea', vivado_place_directive='AltSpreadLogic_high',
        vivado_post_place_phys_opt_directive='AggressiveExplore',
        vivado_route_directive='NoTimingRelaxation')


if __name__ == '__main__':
    main()

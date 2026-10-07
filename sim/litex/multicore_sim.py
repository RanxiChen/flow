#!/usr/bin/env python3
"""BIOS+64 KiB memtest smoke using the FPGA AXI CPU/router and modeled DDR4."""
import argparse
import os
import sys

from migen import Signal
from litex.build.generic_platform import Pins, Subsignal
from litex.build.io import CRG
from litex.build.sim import SimPlatform
from litex.build.sim.config import SimConfig
from litex.soc.cores.cpu import CPUS
from litex.soc.integration.builder import Builder
from litex.soc.integration.soc import SoCRegion
from litex.soc.integration.soc_core import SoCCore
from litedram.modules import EDY4016A
from litedram.phy.model import SDRAMPHYModel

FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
sys.path.insert(0, os.path.join(FLOW_ROOT, 'litex_wrapper'))
from flow import Breeze, BreezeTiny
from flow.axi_router import platform_regions, check_soc_regions
from flow.clint_verilog import BreezeClintVerilog
from flow.plic_verilog import BreezePlicVerilog
from flow.wiring import pack_plic_sources

CPUS['breeze'] = Breeze
CPUS['breeze_tiny'] = BreezeTiny
REGIONS = platform_regions(FLOW_ROOT)
SYS_CLK_FREQ = 100_000_000
MEMTEST_BYTES = 64 * 1024
_IO = [('sys_clk', 0, Pins(1)), ('serial', 0,
    Subsignal('source_valid', Pins(1)), Subsignal('source_ready', Pins(1)),
    Subsignal('source_data', Pins(8)), Subsignal('sink_valid', Pins(1)),
    Subsignal('sink_ready', Pins(1)), Subsignal('sink_data', Pins(8)))]


class MulticoreSimSoC(SoCCore):
    csr_map = {'ctrl': 0, 'uart': 1, 'timer0': 2, 'ddrphy': 3, 'identifier_mem': 4, 'sdram': 5}
    irq_map = {'uart': 10}

    def __init__(self, cluster_profile='single', sys_clk_freq=SYS_CLK_FREQ):
        if cluster_profile not in ('single', 'small'):
            raise ValueError('SoC smoke only supports single/small')
        platform = SimPlatform('SIM', _IO)
        self.submodules.crg = CRG(platform.request('sys_clk'))
        super().__init__(platform, clk_freq=sys_clk_freq, ident='Breeze v1 AXI SoC smoke',
            cpu_type='breeze_tiny' if cluster_profile == 'single' else 'breeze', cpu_variant='standard',
            bus_standard='wishbone', bus_data_width=64, bus_address_width=32,
            bus_bursting=False, bus_interconnect='shared', bus_timeout=None,
            integrated_rom_size=REGIONS['linux_boot_rom']['size'],
            integrated_sram_size=REGIONS['sram']['size'], integrated_main_ram_size=0,
            csr_data_width=32, csr_address_width=14, csr_paging=0x1000,
            with_ctrl=True, with_uart=True, uart_name='sim', with_timer=True)
        sdram_clk_freq = int(sys_clk_freq)
        module = EDY4016A(sdram_clk_freq, '1:4')
        self.submodules.ddrphy = SDRAMPHYModel(module=module, data_width=64, clk_freq=sdram_clk_freq)
        self.add_sdram(name='sdram', phy=self.ddrphy, module=module,
                       size=REGIONS['main_ram']['size'], l2_cache_size=0)
        check_soc_regions(self, FLOW_ROOT)
        self.submodules.machine_timer = BreezeClintVerilog(platform=platform,
            sys_clk_freq=sys_clk_freq, timebase_freq=1_000_000, num_harts=self.cpu.num_harts,
            region_size=REGIONS['machine_timer']['size'], msip_offset=0,
            mtimecmp_offset=0x4000, mtime_offset=0xbff8)
        self.bus.add_slave(name='clint', slave=self.machine_timer.bus,
            region=SoCRegion(origin=REGIONS['machine_timer']['origin'],
                             size=REGIONS['machine_timer']['size'], cached=False))
        self.comb += [self.cpu.msip.eq(self.machine_timer.msip), self.cpu.mtip.eq(self.machine_timer.mtip),
                      self.cpu.time.eq(self.machine_timer.mtime)]
        self.submodules.plic = BreezePlicVerilog(platform=platform,
            num_harts=self.cpu.num_harts, num_sources=31)
        self.bus.add_slave(name='plic', slave=self.plic.bus,
            region=SoCRegion(origin=REGIONS['plic']['origin'], size=REGIONS['plic']['size'], cached=False))
        self.comb += [self.plic.sources.eq(pack_plic_sources(self.uart.ev.irq, first_source=10, num_sources=31)),
                      self.cpu.meip.eq(self.plic.meip), self.cpu.seip.eq(self.plic.seip)]
        for name, value in {'BREEZE_NUM_HARTS': self.cpu.num_harts,
            'BREEZE_CLINT': REGIONS['machine_timer']['origin'],
            'BREEZE_MSIP': REGIONS['machine_timer']['origin'],
            'BREEZE_MTIME': REGIONS['machine_timer']['origin'] + 0xbff8,
            'BREEZE_MTIMECMP': REGIONS['machine_timer']['origin'] + 0x4000,
            'BREEZE_MTIME_FREQUENCY': 1_000_000, 'BREEZE_PLIC': REGIONS['plic']['origin'],
            'BREEZE_UART_PLIC_SOURCE': 10, 'MEMTEST_DATA_SIZE': MEMTEST_BYTES,
            'MEMTEST_ADDR_SIZE': MEMTEST_BYTES, 'CONFIG_BIOS_NO_BOOT': 1}.items():
            self.add_constant(name, value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=('single', 'small'), required=True)
    parser.add_argument('--output-dir', required=True)
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--non-interactive', action='store_true')
    args = parser.parse_args()
    config = SimConfig()
    config.add_clocker('sys_clk', freq_hz=SYS_CLK_FREQ)
    config.add_module('serial2console', 'serial')
    soc = MulticoreSimSoC(args.profile)
    builder = Builder(soc, output_dir=args.output_dir, integrated_rom_auto_size=False,
                      bios_console='lite')
    builder.build(run=args.build, sim_config=config, interactive=not args.non_interactive,
                  trace=False)


if __name__ == '__main__':
    main()

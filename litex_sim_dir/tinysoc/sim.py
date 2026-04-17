import os
import shutil
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
sys.path.append(os.path.join(ROOT, "litex_wrapper"))

from litex.soc.cores.cpu import CPUS
from tinycore.core import TinyCore
from litex.tools import litex_sim as litex_sim_tool
from litex.build.sim import verilator as verilator_tool

CPUS["tinycore"] = TinyCore


class TinySimSoC(litex_sim_tool.SimSoC):
    def __init__(self, **kwargs):
        kwargs.setdefault("cpu_type", "tinycore")
        kwargs.setdefault("cpu_variant", "tiny")
        kwargs.setdefault("integrated_rom_size", 0x8000)
        kwargs.setdefault("integrated_sram_size", 0x4000)
        kwargs.setdefault("uart_name", "serial")
        super().__init__(**kwargs)


litex_sim_tool.SimSoC = TinySimSoC


def _enable_chisel_printf():
    original_core_dir = verilator_tool.core_directory
    patched_core_dir  = os.path.join(os.path.dirname(__file__), "_litex_sim_core")
    original_makefile = os.path.join(original_core_dir, "Makefile")
    patched_makefile  = os.path.join(patched_core_dir, "Makefile")

    if not os.path.exists(patched_core_dir):
        shutil.copytree(original_core_dir, patched_core_dir)

    with open(original_makefile, "r", encoding="utf-8") as f:
        makefile_content = f.read()
    makefile_content = makefile_content.replace("-DPRINTF_COND=0", "-DPRINTF_COND=1")

    with open(patched_makefile, "w", encoding="utf-8") as f:
        f.write(makefile_content)

    verilator_tool.core_directory = patched_core_dir


def main():
    _enable_chisel_printf()
    original_generate_gtkw_savefile = litex_sim_tool.generate_gtkw_savefile

    def _safe_generate_gtkw_savefile(*args, **kwargs):
        try:
            return original_generate_gtkw_savefile(*args, **kwargs)
        except ModuleNotFoundError as e:
            if getattr(e, "name", None) == "vcd":
                print("[tinysoc] python package 'vcd' not found, skip .gtkw savefile generation and keep running with raw VCD trace.")
                return None
            raise

    litex_sim_tool.generate_gtkw_savefile = _safe_generate_gtkw_savefile
    litex_sim_tool.main()


if __name__ == "__main__":
    main()

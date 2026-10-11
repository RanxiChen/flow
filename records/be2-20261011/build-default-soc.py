"""BE2 S1: production single hart, KCU105 100 MHz, default Vivado directives."""
import pathlib
import sys
import subprocess
import re
import os

repo = pathlib.Path(sys.argv[1]).resolve()
out = pathlib.Path(sys.argv[2]).resolve()
profile = sys.argv[3] if len(sys.argv)>3 else 'single'
assert profile in ('single','small')
generate_only = '--generate-only' in sys.argv[4:]
synth_only = '--synth-only' in sys.argv[4:]
sys.path.insert(0,str(repo/'fpga/kcu105'))
import target
soc = target.BreezeKCU105SoC('breeze' if profile=='small' else 'breeze-tiny', False, 100_000_000)
threads=int(os.environ.get('BE2_VIVADO_THREADS','4'))
assert 1 <= threads <= 4
soc.platform.toolchain.pre_synthesis_commands.append(f'set_param general.maxThreads {threads}')
soc.platform.toolchain.additional_commands += [
    'report_timing_summary -file be2-timing-summary.rpt',
    'report_timing -max_paths 20 -path_type full -file be2-worst-20.rpt',
    'report_utilization -hierarchical -file be2-utilization-hierarchical.rpt',
    'report_utilization -file be2-utilization.rpt',
]
builder = target.SnapshotBuilder(soc, output_dir=str(out), csr_csv=str(out/'csr.csv'),
    csr_json=str(out/'csr.json'), integrated_rom_auto_size=False, bios_console='lite')
# No timing exceptions or non-default synth/opt/place/route directives added.
# The installed toolchain writes a bitstream unconditionally, regardless of
# build_bitstream kwargs. Prepare its default Tcl, then omit that command.
builder.build(run=False)
gateware = out/'gateware'
tcl = gateware/'xilinx_kcu105.tcl'
text = tcl.read_text()
text, count = re.subn(r'^write_bitstream[^\n]*$', '# BE2 scope ends at routed evidence; no bitstream.', text, flags=re.M)
assert count == 1, 'unexpected toolchain bitstream command'
if synth_only:
    point = text.index('opt_design')
    text = text[:point] + 'report_utilization -hierarchical -file be2-utilization-hierarchical.rpt\nreport_timing_summary -file be2-timing-summary.rpt\nexit\n'
tcl.write_text(text)
if not generate_only:
    subprocess.run(['bash','build_xilinx_kcu105.sh'], cwd=gateware, check=True)

#!/usr/bin/env bash
# Reuse an exact completed routed input in a fresh product directory.
set -Eeuo pipefail
input=${1:?absolute original hardware directory}
output=${2:?absolute fresh candidate directory}
script=${3:?reviewed postroute Tcl}
checkpoint_name=${4:-xilinx_kcu105_route.dcp}
case "$checkpoint_name" in xilinx_kcu105_route.dcp|clock-placed.dcp) ;; *) exit 2;; esac
affinity=${BE2_CLOSURE_AFFINITY:-0-3}
case "$affinity" in 0-3|4-7|6-9) ;; *) exit 2;; esac
case "$input:$output:$script" in /*:/*:/*) ;; *) exit 2;; esac
test -s "$input/gateware/$checkpoint_name"
test ! -e "$output"
cp -a --reflink=auto "$input" "$output"
rm -f "$output/postroute-exit-code.txt"
trap 'rc=$?; echo "$rc" > "$output/postroute-exit-code.txt"' EXIT
python3 - "$input" "$output" "$script" "$checkpoint_name" <<'PY'
import datetime,hashlib,json,os,pathlib,re,sys
original,dest,tcl=map(pathlib.Path,sys.argv[1:4])
checkpoint_name=sys.argv[4]
script_text=tcl.read_text()
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
binding=json.loads((original/'build-binding.json').read_text())
record=dict(original=str(original),source_sha=binding['source_sha'],
 input_checkpoint_filename=checkpoint_name,
 input_checkpoint_sha256=sha(original/'gateware'/checkpoint_name),
 script_sha256=sha(tcl),host='Alan',clock_hz=100000000,
 vivado='2022.2',maxThreads=4,affinity=os.environ.get('BE2_CLOSURE_AFFINITY','0-3'),nice=10,
 directives=([x for token,x in [('phys_opt_design -directive AggressiveExplore','AggressiveExplore'),
             ('route_design -directive NoTimingRelaxation','NoTimingRelaxation'),
             ('-tns_cleanup','tns_cleanup')] if token in script_text]),
 constraints_unchanged=True,new_timing_exceptions=False,
 start_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),board='NOT_RUN')
if 'force_replication_on_nets' in tcl.read_text():
 record['directives'].insert(0,'force_replication_on_nets on failing register startpoints')
 record['netlist_attribute_change']='DONT_TOUCH cleared only on selected failing register drivers/nets'
if 'force_replication_on_nets' in tcl.read_text() and 'route_design -unroute\n' in tcl.read_text():
 record['directives'].insert(0,'unroute preserving placement for pre-route replication')
 if 'tns_cleanup' in record['directives']: record['directives'].remove('tns_cleanup')
if 'set_property CLOCK_DELAY_GROUP' in tcl.read_text():
 record['physical_clock_routing_property']='CLOCK_DELAY_GROUP be2_ddr_serdes on BUFGCE/O and BUFGCE_DIV/O nets'
 record['directives']=['partial clock unroute','NoTimingRelaxation','tns_cleanup']
 if 'set_property USER_CLOCK_ROOT' in tcl.read_text():
  root=('original default DCP measured common X1Y2' if 'set common_root X1Y2' in tcl.read_text() else
        'sys4x_clk' if 'get_property CLOCK_ROOT $fast_net' in tcl.read_text() else 'sys_clk')
  record['physical_clock_routing_property'] += '; USER_CLOCK_ROOT both outputs set to existing ' + root + ' CLOCK_ROOT'
 record['constraints_unchanged']=False
 record['timing_constraints_unchanged']=True
 if 'place_design -directive Quick' in tcl.read_text():
  record['directives']=['freeze primitive LOC/BEL','unroute all','place unplace','place Quick no_psip no_bufg_opt','NoTimingRelaxation','tns_cleanup']
  record['physical_cell_constraint']=('all primitive LOC fixed, simple LUT/FF BEL fixed; RAM/SRL internal BEL packing allowed' if 'set bel_cells' in tcl.read_text() else 'all primitive LOC/BEL fixed') + '; actual equality and placed common clock roots required before routing'
comparison=re.search(r'^set comparison_checkpoint (/[^\n]+)$',script_text,re.M)
if comparison:
 comparison_path=pathlib.Path(comparison[1])
 comparison_binding=json.loads((comparison_path.parent.parent/'build-binding.json').read_text())
 if comparison_binding['source_sha'] != binding['source_sha']: raise RuntimeError('Placement comparison source mismatch')
 record['placement_comparison_checkpoint']=str(comparison_path)
 record['placement_comparison_sha256']=sha(comparison_path)
if 'compare-placed-cells.py' in script_text:
 record['placement_auditor_sha256']=sha(tcl.parent/'compare-placed-cells.py')
prior=original/'physical-candidate.json'
if prior.is_file(): record['parent_candidate']=json.loads(prior.read_text())
(dest/'physical-candidate.json').write_text(json.dumps(record,indent=2)+'\n')
PY
cp "$script" "$output/gateware/postroute-closure.tcl"
if [[ -f "$(dirname "$script")/compare-placed-cells.py" ]]; then
 cp "$(dirname "$script")/compare-placed-cells.py" "$output/gateware/compare-placed-cells.py"
fi
cp "$(dirname "$script")/routed_timing_gate.tcl" "$output/gateware/routed_timing_gate.tcl"
cd "$output/gateware"
# A copied old bitstream must never survive a failing candidate gate.
if [[ -f xilinx_kcu105.bit ]]; then mv xilinx_kcu105.bit input-reference.bit; fi
taskset -c "$affinity" nice -n 10 /home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado \
 -mode batch -source postroute-closure.tcl \
 -tclargs "$input/gateware/$checkpoint_name" \
 > "$output/postroute-vivado.log" 2>&1
test -s xilinx_kcu105.bit
sha256sum xilinx_kcu105.bit ../software/bios/bios.bin > "$output/product-sha256.txt"

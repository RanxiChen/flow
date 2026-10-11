#!/usr/bin/env bash
# Reuse an exact completed routed input in a fresh product directory.
set -Eeuo pipefail
input=${1:?absolute original hardware directory}
output=${2:?absolute fresh candidate directory}
script=${3:?reviewed postroute Tcl}
affinity=${BE2_CLOSURE_AFFINITY:-0-3}
case "$affinity" in 0-3|4-7|6-9) ;; *) exit 2;; esac
case "$input:$output:$script" in /*:/*:/*) ;; *) exit 2;; esac
test -s "$input/gateware/xilinx_kcu105_route.dcp"
test ! -e "$output"
cp -a --reflink=auto "$input" "$output"
rm -f "$output/postroute-exit-code.txt"
trap 'rc=$?; echo "$rc" > "$output/postroute-exit-code.txt"' EXIT
python3 - "$input" "$output" "$script" <<'PY'
import datetime,hashlib,json,os,pathlib,sys
original,dest,tcl=map(pathlib.Path,sys.argv[1:])
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
binding=json.loads((original/'build-binding.json').read_text())
record=dict(original=str(original),source_sha=binding['source_sha'],
 input_checkpoint_sha256=sha(original/'gateware/xilinx_kcu105_route.dcp'),
 script_sha256=sha(tcl),host='Alan',clock_hz=100000000,
 vivado='2022.2',maxThreads=4,affinity=os.environ.get('BE2_CLOSURE_AFFINITY','0-3'),nice=10,
 directives=['AggressiveExplore','NoTimingRelaxation','tns_cleanup'],
 constraints_unchanged=True,new_timing_exceptions=False,
 start_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),board='NOT_RUN')
if 'force_replication_on_nets' in tcl.read_text():
 record['directives'].insert(0,'force_replication_on_nets on failing register startpoints')
 record['netlist_attribute_change']='DONT_TOUCH cleared only on selected failing register drivers/nets'
if 'route_design -unroute\n' in tcl.read_text():
 record['directives'].insert(0,'unroute preserving placement for pre-route replication')
 record['directives'].remove('tns_cleanup')
if 'CLOCK_DELAY_GROUP' in tcl.read_text():
 record['physical_clock_routing_property']='CLOCK_DELAY_GROUP be2_ddr_serdes on BUFGCE/O and BUFGCE_DIV/O nets'
 record['directives']=['partial clock unroute','NoTimingRelaxation','tns_cleanup']
 if 'set_property USER_CLOCK_ROOT' in tcl.read_text():
  root=('original default DCP measured common X1Y2' if 'set common_root X1Y2' in tcl.read_text() else
        'sys4x_clk' if 'get_property CLOCK_ROOT $fast_net' in tcl.read_text() else 'sys_clk')
  record['physical_clock_routing_property'] += '; USER_CLOCK_ROOT both outputs set to existing ' + root + ' CLOCK_ROOT'
 record['constraints_unchanged']=False
 record['timing_constraints_unchanged']=True
prior=original/'physical-candidate.json'
if prior.is_file(): record['parent_candidate']=json.loads(prior.read_text())
(dest/'physical-candidate.json').write_text(json.dumps(record,indent=2)+'\n')
PY
cp "$script" "$output/gateware/postroute-closure.tcl"
cp "$(dirname "$script")/routed_timing_gate.tcl" "$output/gateware/routed_timing_gate.tcl"
cd "$output/gateware"
# A copied old bitstream must never survive a failing candidate gate.
if [[ -f xilinx_kcu105.bit ]]; then mv xilinx_kcu105.bit input-reference.bit; fi
taskset -c "$affinity" nice -n 10 /home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado \
 -mode batch -source postroute-closure.tcl \
 -tclargs "$input/gateware/xilinx_kcu105_route.dcp" \
 > "$output/postroute-vivado.log" 2>&1
test -s xilinx_kcu105.bit
sha256sum xilinx_kcu105.bit ../software/bios/bios.bin > "$output/product-sha256.txt"

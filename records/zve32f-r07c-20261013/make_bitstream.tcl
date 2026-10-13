# Run only for a routed strategy whose final timing has passed.
# vivado -mode batch -source make_bitstream.tcl -tclargs GATEWARE
if {$argc != 1} {error "usage: make_bitstream.tcl GATEWARE"}
set gateware [lindex $argv 0]
set_param general.maxThreads 4
open_checkpoint routed.dcp
source $gateware/flow_sd_timing.tcl
source $gateware/routed_timing_gate.tcl
report_timing_summary -delay_type min_max -report_unconstrained -file bitstream-timing-gate.rpt
flow_require_routed_timing bitstream-timing-gate.rpt
set setup_path [get_timing_paths -quiet -delay_type max -max_paths 1]
set hold_path [get_timing_paths -quiet -delay_type min -max_paths 1]
if {![llength $setup_path] || ![llength $hold_path]} {error "missing routed timing paths"}
if {[get_property SLACK $setup_path] < 0 || [get_property SLACK $hold_path] < 0} {
    error "routed setup/hold failed; bitstream withheld"
}
flow_report_sd_timing
write_bitstream -force xilinx_kcu105.bit
write_debug_probes -force xilinx_kcu105.ltx
quit

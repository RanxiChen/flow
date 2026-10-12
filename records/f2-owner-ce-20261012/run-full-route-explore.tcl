# Resume the production KU040 implementation from the exact synthesis DCP.
# Run from a fresh evidence directory on Alan. No constraints are relaxed.
set gateware /home/chen/FUN/flow-runs/f2-owner-ce-d4350cb-retry2-20261012/single/gateware
set source_snapshot /home/chen/FUN/flow-runs/f2-owner-ce-d4350cb-retry2-20261012/single/source-snapshot
set_param general.maxThreads 4
open_checkpoint $gateware/xilinx_kcu105_synth.dcp
source $gateware/flow_sd_timing.tcl
source $source_snapshot/soc2-bram-gate.tcl
opt_design -directive default
place_design -directive Explore
phys_opt_design -directive Explore
report_utilization -hierarchical -file xilinx_kcu105_utilization_hierarchical_place.rpt
report_utilization -file xilinx_kcu105_utilization_place.rpt
report_io -file xilinx_kcu105_io.rpt
report_control_sets -verbose -file xilinx_kcu105_control_sets.rpt
report_clock_utilization -file xilinx_kcu105_clock_utilization.rpt
write_checkpoint -force xilinx_kcu105_place.dcp
report_timing_summary -delay_type min_max -report_unconstrained -file post-place-timing.rpt
route_design -directive NoTimingRelaxation
phys_opt_design -directive default
write_checkpoint -force xilinx_kcu105_route.dcp
report_route_status -file xilinx_kcu105_route_status.rpt
report_drc -file xilinx_kcu105_drc.rpt
report_timing_summary -datasheet -max_paths 10 -file xilinx_kcu105_timing.rpt
report_power -file xilinx_kcu105_power.rpt
report_timing_summary -delay_type min_max -report_unconstrained -file routed-generation-gate.rpt
report_timing -max_paths 30 -path_type full -file soc-worst-30.rpt
check_timing -verbose -file check-timing.rpt
report_methodology -file methodology.rpt
source $gateware/routed_timing_gate.tcl
flow_require_routed_timing routed-generation-gate.rpt
set setup_path [get_timing_paths -quiet -delay_type max -max_paths 1]
set hold_path [get_timing_paths -quiet -delay_type min -max_paths 1]
if {![llength $setup_path] || ![llength $hold_path]} { error "Missing routed timing paths" }
if {[get_property SLACK $setup_path] < 0 || [get_property SLACK $hold_path] < 0} { error "Routed setup/hold failed; bitstream withheld" }
write_bitstream -force xilinx_kcu105.bit
write_debug_probes -force xilinx_kcu105.ltx
flow_report_sd_timing
quit

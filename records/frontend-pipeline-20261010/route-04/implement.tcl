set_param general.maxThreads 4
set rtl_dir [lindex $argv 0]
set xdc_file [lindex $argv 1]
set part xcku040-ffva1156-2-e
create_project -in_memory -part $part
set sources [glob -nocomplain $rtl_dir/*.sv]
if {[llength $sources] == 0} { error "no generated frontend RTL" }
set_property verilog_define SYNTHESIS [current_fileset]
read_verilog -sv $sources
read_xdc $xdc_file
synth_design -top BreezeFrontendTimingHarness -mode out_of_context -part $part -flatten_hierarchy rebuilt
write_checkpoint -force synth.dcp
report_utilization -hierarchical -file utilization-synth.rpt
report_timing_summary -delay_type min_max -report_unconstrained -file timing-synth.rpt
opt_design
place_design
phys_opt_design -directive AggressiveExplore
write_checkpoint -force place.dcp
report_timing_summary -delay_type min_max -report_unconstrained -file timing-place.rpt
route_design -directive NoTimingRelaxation
phys_opt_design -directive AggressiveExplore
write_checkpoint -force route.dcp
report_utilization -hierarchical -file utilization-route.rpt
report_timing_summary -delay_type min_max -report_unconstrained -file timing-route.rpt
report_timing -delay_type max -max_paths 20 -path_type full_clock_expanded -file worst-setup.rpt
report_timing -delay_type min -max_paths 20 -path_type full_clock_expanded -file worst-hold.rpt
report_route_status -file route-status.rpt
check_timing -verbose -file check-timing.rpt
report_drc -file drc.rpt
set setup [get_timing_paths -delay_type max -max_paths 1]
set hold [get_timing_paths -delay_type min -max_paths 1]
if {[llength $setup] != 1 || [llength $hold] != 1} { error "missing setup/hold paths" }
set wns [get_property SLACK $setup]
set whs [get_property SLACK $hold]
set f [open gate.json w]
puts $f "{\"top\":\"BreezeFrontendTimingHarness\",\"part\":\"$part\",\"period_ns\":10.0,\"clock_uncertainty_ns\":0.1,\"wns_ns\":$wns,\"whs_ns\":$whs}"
close $f
if {$wns < 0 || $whs < 0} { error "frontend 100 MHz timing failed: WNS=$wns WHS=$whs" }

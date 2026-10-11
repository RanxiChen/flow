set_param general.maxThreads 4
open_checkpoint [lindex $argv 0]
if {[get_property PERIOD [get_clocks sys_clk]] != 10.0} {error "Expected unchanged 100MHz sys_clk"}
report_timing_summary -delay_type min_max -file baseline-timing.rpt
report_exceptions -file baseline-exceptions.rpt
# Check persistent data placement against the exact pre-clock source checkpoint.
set comparison_checkpoint /home/chen/FUN/flow-runs/be2-d902fb5-rvv-placed-replication-sd/gateware/xilinx_kcu105_route.dcp
close_design
open_checkpoint $comparison_checkpoint
# The placer recreates IDELAYCTRL and its reduction LUTs normally.
# Their recreated network remains subject to final DRC/timing; CPU/memory/IO
# data cell positions are checked by persistent NAME/value, never stale handles.
set expected_cells [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != "" && NAME !~ IDELAYCTRL*}]
set loc_before [dict create]
foreach name [get_property NAME $expected_cells] loc [get_property LOC $expected_cells] {dict set loc_before $name $loc}
set expected_bels [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != "" && NAME !~ IDELAYCTRL* && (REF_NAME =~ LUT? || REF_NAME =~ FD?E)}]
set bel_before [dict create]
foreach name [get_property NAME $expected_bels] bel [get_property BEL $expected_bels] {dict set bel_before $name $bel}
close_design
open_checkpoint [lindex $argv 0]
set fresh_cells [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != "" && NAME !~ IDELAYCTRL*}]
set loc_after [dict create]
foreach name [get_property NAME $fresh_cells] loc [get_property LOC $fresh_cells] {dict set loc_after $name $loc}
dict for {name loc} $loc_before {
    if {![dict exists $loc_after $name] || [dict get $loc_after $name] ne $loc} {error "Persistent data primitive LOC changed: $name"}
}
set fresh_bels [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != "" && NAME !~ IDELAYCTRL* && (REF_NAME =~ LUT? || REF_NAME =~ FD?E)}]
set bel_after [dict create]
foreach name [get_property NAME $fresh_bels] bel [get_property BEL $fresh_bels] {dict set bel_after $name $bel}
dict for {name bel} $bel_before {
    if {![dict exists $bel_after $name] || [dict get $bel_after $name] ne $bel} {error "Simple data LUT/FF BEL changed: $name"}
}
set audit [open clock-resume-audit.txt w]
puts $audit "PERSISTENT_DATA_LOC_CHECKED=[dict size $loc_before] SIMPLE_DATA_BEL_CHECKED=[dict size $bel_before] ALL_UNCHANGED=1"
set fast_net [get_nets -of_objects [get_pins BUFGCE/O]]
set slow_net [get_nets -of_objects [get_pins BUFGCE_DIV/O]]
foreach net [concat $fast_net $slow_net] {
    set root [get_property CLOCK_ROOT $net]
    puts $audit "PLACED NET=$net CLOCK_ROOT=$root USER_CLOCK_ROOT=[get_property USER_CLOCK_ROOT $net] GROUP=[get_property CLOCK_DELAY_GROUP $net]"
    if {$root ne "X1Y2" || [get_property CLOCK_DELAY_GROUP $net] ne "be2_ddr_serdes"} {error "Expected common placed clock root/group absent"}
}
flush $audit
route_design -directive NoTimingRelaxation -tns_cleanup
foreach net [concat $fast_net $slow_net] {puts $audit "AFTER NET=$net CLOCK_ROOT=[get_property CLOCK_ROOT $net] USER_CLOCK_ROOT=[get_property USER_CLOCK_ROOT $net] GROUP=[get_property CLOCK_DELAY_GROUP $net]"}
close $audit
report_clocks -file clock-balanced-clocks.rpt
report_clock_interaction -file clock-balanced-interaction.rpt
report_cdc -file clock-balanced-cdc.rpt
write_checkpoint -force xilinx_kcu105_route.dcp
report_timing_summary -delay_type min_max -report_unconstrained -file xilinx_kcu105_timing.rpt
report_route_status -file xilinx_kcu105_route_status.rpt
report_drc -file xilinx_kcu105_drc.rpt
report_exceptions -file final-exceptions.rpt
report_utilization -file soc-utilization.rpt
report_timing -max_paths 10 -path_type full -file soc-worst-10.rpt
source routed_timing_gate.tcl
flow_require_routed_timing xilinx_kcu105_timing.rpt
set setup_path [get_timing_paths -quiet -delay_type max -max_paths 1]
set hold_path [get_timing_paths -quiet -delay_type min -max_paths 1]
if {![llength $setup_path] || ![llength $hold_path]} {error "Missing routed timing paths"}
if {[get_property SLACK $setup_path] < 0 || [get_property SLACK $hold_path] < 0} {
    error "Routed setup/hold failed; bitstream withheld"
}
set stream [open xilinx_kcu105_route_status.rpt r]
set route_status [read $stream]
close $stream
if {![regexp {# of nets with routing errors\.+[ \t]*:[ \t]*([0-9]+)} $route_status match route_errors] || $route_errors != 0} {
    error "Routing errors or missing route status; bitstream withheld"
}
# Reuse the original SD IO selection and 10ns transport budgets.
source flow_sd_timing.tcl
flow_report_sd_timing
write_bitstream -force xilinx_kcu105.bit
report_timing_summary -delay_type min_max -report_unconstrained -file soc-timing-summary.rpt
exit

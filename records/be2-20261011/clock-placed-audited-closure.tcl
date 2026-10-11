# Source/config/constraints stay those of the exact input checkpoint.
# Run in a fresh copied product directory; never overwrite the baseline.
set_param general.maxThreads 4
open_checkpoint [lindex $argv 0]
# No IO exclusion or relaxed clock/exception is applied.
if {[get_property PERIOD [get_clocks sys_clk]] != 10.0} {error "Expected unchanged 100MHz sys_clk"}
report_timing_summary -delay_type min_max -file baseline-timing.rpt
report_timing -max_paths 20 -path_type full -file baseline-worst-20.rpt
report_exceptions -file baseline-exceptions.rpt
# Related DDR clocks share the same MMCM output and parallel BUFG inputs.
set fast_cell [get_cells BUFGCE]
set slow_cell [get_cells BUFGCE_DIV]
if {[llength $fast_cell] != 1 || [llength $slow_cell] != 1} {error "DDR clock buffer selection incomplete"}
set fast_input [get_nets -of_objects [get_pins BUFGCE/I]]
set slow_input [get_nets -of_objects [get_pins BUFGCE_DIV/I]]
if {$fast_input ne $slow_input} {error "DDR buffers do not have the expected common source"}
set fast_net [get_nets -of_objects [get_pins BUFGCE/O]]
set slow_net [get_nets -of_objects [get_pins BUFGCE_DIV/O]]
set fast_clock [get_clocks -of_objects [get_pins BUFGCE/O]]
if {[llength $fast_clock] != 1 || [get_property PERIOD $fast_clock] != 2.5} {error "Expected unchanged 400MHz DDR clock"}
set audit [open clock-routing-properties.txt w]
puts $audit "FAST_INPUT=$fast_input SLOW_INPUT=$slow_input FAST_NET=$fast_net SLOW_NET=$slow_net"
foreach cell [concat $fast_cell $slow_cell] {puts $audit "CELL=$cell LOC=[get_property LOC $cell]"}
foreach net [concat $fast_net $slow_net] {puts $audit "BEFORE NET=$net CLOCK_ROOT=[get_property CLOCK_ROOT $net] USER_CLOCK_ROOT=[get_property USER_CLOCK_ROOT $net] GROUP=[get_property CLOCK_DELAY_GROUP $net]"}
# Read-only original default DCP audit measured both roots X1Y2.
# Original source remains d902fb5; only selected physical clock root differs.
set common_root X1Y2
if {$common_root eq "" || [llength [get_clock_regions $common_root]] != 1} {error "Missing existing system clock root"}
puts $audit "COMMON_ROOT=$common_root measured in original default d902 clock audit (both clocks X1Y2); no frequency/waveform/skew relaxation"
# Freeze existing primitive positions, then rerun clock resource placement.
# Quick performs no timing-driven data placement; PSIP/global-buffer insertion disabled.
set fixed_cells [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != ""}]
if {[llength $fixed_cells] < 100000} {error "Unexpectedly incomplete primitive placement selection"}
# Collection handles can become invalid when the placer rebuilds primitive objects.
# Keep name/value snapshots, not handles, for the post-place audit.
set audit_cells [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != "" && REF_NAME != IDELAYCTRL}]
set loc_before [dict create]
foreach name [get_property NAME $audit_cells] loc [get_property LOC $audit_cells] {dict set loc_before $name $loc}
set bel_cells [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != "" && (REF_NAME =~ LUT? || REF_NAME =~ FD?E)}]
set bel_before [dict create]
foreach name [get_property NAME $bel_cells] bel [get_property BEL $bel_cells] {dict set bel_before $name $bel}
set_property IS_LOC_FIXED true $fixed_cells
set_property IS_BEL_FIXED true $bel_cells
puts $audit "FIXED_LOC_PRIMITIVES=[llength $fixed_cells] FIXED_SIMPLE_LUT_FF_BELS=[llength $bel_cells] RAM_INTERNAL_BEL_PACKING_ALLOWED=1"
flush $audit
route_design -unroute
set_property USER_CLOCK_ROOT $common_root [concat $fast_net $slow_net]
set_property CLOCK_DELAY_GROUP be2_ddr_serdes [concat $fast_net $slow_net]
place_design -unplace
place_design -directive Quick -no_psip -no_bufg_opt
# IDELAYCTRL replicas are recreated normally by placement; CPU/memory/IO
# data cells retain their fixed LOC, and simple LUT/FF BEL values are audited.
set fast_net [get_nets -of_objects [get_pins BUFGCE/O]]
set slow_net [get_nets -of_objects [get_pins BUFGCE_DIV/O]]
write_checkpoint -force clock-placed.dcp
set fresh_cells [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != "" && REF_NAME != IDELAYCTRL}]
set loc_after [dict create]
foreach name [get_property NAME $fresh_cells] loc [get_property LOC $fresh_cells] {dict set loc_after $name $loc}
dict for {name loc} $loc_before {
    if {![dict exists $loc_after $name] || [dict get $loc_after $name] ne $loc} {error "Persistent primitive LOC changed: $name"}
}
set fresh_bel_cells [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != "" && (REF_NAME =~ LUT? || REF_NAME =~ FD?E)}]
set bel_after [dict create]
foreach name [get_property NAME $fresh_bel_cells] bel [get_property BEL $fresh_bel_cells] {dict set bel_after $name $bel}
dict for {name bel} $bel_before {
    if {![dict exists $bel_after $name] || [dict get $bel_after $name] ne $bel} {error "Simple LUT/FF BEL changed: $name"}
}
puts $audit "ALL_PRIMITIVE_LOC_AND_SIMPLE_LUT_FF_BEL_UNCHANGED=1"
foreach net [concat $fast_net $slow_net] {
    set root [get_property CLOCK_ROOT $net]
    puts $audit "PLACED NET=$net CLOCK_ROOT=$root REQUESTED=$common_root"
    if {$root ne $common_root} {error "Requested physical clock root was not placed; route/bit withheld"}
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

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
set loc_before [get_property LOC $fixed_cells]
set bel_before [get_property BEL $fixed_cells]
set_property IS_LOC_FIXED true $fixed_cells
set_property IS_BEL_FIXED true $fixed_cells
puts $audit "FIXED_PRIMITIVES=[llength $fixed_cells]"
flush $audit
route_design -unroute
set_property USER_CLOCK_ROOT $common_root [concat $fast_net $slow_net]
set_property CLOCK_DELAY_GROUP be2_ddr_serdes [concat $fast_net $slow_net]
place_design -unplace
place_design -directive Quick -no_psip -no_bufg_opt
if {[get_property LOC $fixed_cells] ne $loc_before || [get_property BEL $fixed_cells] ne $bel_before} {error "Fixed primitive LOC/BEL changed"}
puts $audit "FIXED_PRIMITIVE_LOC_BEL_UNCHANGED=1"
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

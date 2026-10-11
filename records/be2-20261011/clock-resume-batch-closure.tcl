set_param general.maxThreads 4
set comparison_checkpoint /home/chen/FUN/flow-runs/be2-d902fb5-rvv-placed-replication-sd/gateware/xilinx_kcu105_route.dcp
proc flow_dump_data_cells {path} {
    set cells [get_cells -hierarchical -filter {IS_PRIMITIVE == 1 && LOC != "" && BEL != "" && NAME !~ IDELAYCTRL*}]
    set out [open $path w]
    puts $out "name\tref\tloc\tbel"
    foreach name [get_property NAME $cells] ref [get_property REF_NAME $cells] loc [get_property LOC $cells] bel [get_property BEL $cells] {
        puts $out "$name\t$ref\t$loc\t$bel"
    }
    close $out
}
open_checkpoint $comparison_checkpoint
flow_dump_data_cells original-data-placement.tsv
close_design
open_checkpoint [lindex $argv 0]
if {[get_property PERIOD [get_clocks sys_clk]] != 10.0} {error "Expected unchanged 100MHz sys_clk"}
report_timing_summary -delay_type min_max -file baseline-timing.rpt
report_exceptions -file baseline-exceptions.rpt
flow_dump_data_cells clock-data-placement.tsv
puts [exec python3 compare-placed-cells.py original-data-placement.tsv clock-data-placement.tsv placement-audit.json]
set audit [open clock-resume-audit.txt w]
puts $audit "PERSISTENT_DATA_POSITION_AUDIT=PASS see placement-audit.json"
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

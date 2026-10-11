# Read-only audit of the exact pre-replication RVV route checkpoint.
set_param general.maxThreads 2
open_checkpoint [lindex $argv 0]
set out [open clock-route-properties.txt w]
foreach name {sys_clk sys4x_clk} {
    set net [get_nets $name]
    puts $out "NET=$net ROOT=[get_property CLOCK_ROOT $net] USER_ROOT=[get_property USER_CLOCK_ROOT $net] GROUP=[get_property CLOCK_DELAY_GROUP $net]"
    set route_out [open ${name}.route.txt w]
    puts $route_out [get_property ROUTE $net]
    close $route_out
}
foreach name {BUFGCE BUFGCE_DIV MMCME2_ADV} {
    set cell [get_cells $name]
    if {[llength $cell] == 1} {puts $out "CELL=$cell REF=[get_property REF_NAME $cell] LOC=[get_property LOC $cell]"}
}
close $out
report_clocks -file original-clocks.rpt
report_timing_summary -delay_type min_max -report_unconstrained -file original-timing.rpt
report_timing -path_type full_clock_expanded -to [get_pins OSERDESE3_76/D1] -max_paths 1 -file original-ddr-slow-path.rpt
report_timing -path_type full_clock_expanded -from [get_pins OSERDESE3_76/OQ] -max_paths 1 -file original-ddr-fast-path.rpt
exit

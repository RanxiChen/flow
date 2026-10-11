# Source/config/constraints stay those of the exact input checkpoint.
# Run in a fresh copied product directory; never overwrite the baseline.
set_param general.maxThreads 4
open_checkpoint [lindex $argv 0]
# No IO exclusion or relaxed clock/exception is applied.
if {[get_property PERIOD [get_clocks sys_clk]] != 10.0} {error "Expected unchanged 100MHz sys_clk"}
report_timing_summary -delay_type min_max -file baseline-timing.rpt
report_timing -max_paths 20 -path_type full -file baseline-worst-20.rpt
report_exceptions -file baseline-exceptions.rpt
# Replicate only register drivers found on the exact negative setup paths.
# This preserves input/clock/init and latency; no retiming or timing exception.
set failing [get_timing_paths -quiet -delay_type max -slack_lesser_than 0 -max_paths 100]
set target_cells {}
set target_nets {}
set target_file [open targeted-replication.txt w]
foreach path $failing {
    set cell [get_cells -of_objects [get_pins [get_property STARTPOINT_PIN $path]]]
    if {[llength $cell] == 1 && [regexp {^FD[RCPS]E$} [get_property REF_NAME $cell]]} {
        set q [get_pins -of_objects $cell -filter {REF_PIN_NAME == Q}]
        set net [get_nets -segments -of_objects $q]
        if {[llength $net]} {
            puts $target_file "CELL=$cell NET=$net DONT_TOUCH=[get_property DONT_TOUCH $cell] FANOUT=[llength [get_pins -of_objects $net -filter {DIRECTION == IN}]]"
            lappend target_cells $cell
            lappend target_nets $net
        }
    }
}
close $target_file
set target_cells [lsort -unique $target_cells]
set target_nets [lsort -unique $target_nets]
if {![llength $target_nets]} {error "No failing register drivers; targeted candidate has no basis"}
set_property DONT_TOUCH false $target_cells
set target_nets [get_nets -segments -of_objects [get_pins -of_objects $target_cells -filter {REF_PIN_NAME == Q}]]
if {![llength $target_nets]} {error "Selected register Q nets disappeared"}
set_property DONT_TOUCH false $target_nets
# Vivado 2022.2 force replication is supported in pre-route mode only.
# Retain placement, input/clock/init/latency and all original constraints.
route_design -unroute
phys_opt_design -force_replication_on_nets $target_nets
report_timing_summary -delay_type min_max -file after-replication-timing.rpt
phys_opt_design -directive AggressiveExplore
route_design -directive NoTimingRelaxation
phys_opt_design -directive AggressiveExplore
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

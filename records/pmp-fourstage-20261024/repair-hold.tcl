set_param general.maxThreads 4
set checkpoint [file normalize [lindex $argv 0]]
set common_dir [file normalize [lindex $argv 1]]
set part xcku040-ffva1156-2-e
open_checkpoint $checkpoint
phys_opt_design -aggressive_hold_fix
route_design -directive NoTimingRelaxation
write_checkpoint -force route.dcp
report_timing_summary -delay_type min_max -report_unconstrained -file timing-route.rpt
report_timing -delay_type max -max_paths 30 -path_type full_clock_expanded -file worst-setup.rpt
report_timing -delay_type min -max_paths 30 -path_type full_clock_expanded -file worst-hold.rpt
report_utilization -hierarchical -file utilization-route.rpt
report_route_status -file route-status.rpt
report_clocks -file clocks.rpt
report_exceptions -file exceptions.rpt
check_timing -verbose -file check-timing.rpt
report_drc -file drc.rpt
source $common_dir/summarize.tcl

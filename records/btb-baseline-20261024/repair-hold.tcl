set_param general.maxThreads 4
set checkpoint [file normalize [lindex $argv 0]]
set common_dir [file normalize [lindex $argv 1]]
open_checkpoint $checkpoint
phys_opt_design -aggressive_hold_fix
route_design -directive NoTimingRelaxation
write_checkpoint -force route.dcp
report_timing_summary -delay_type min_max -report_unconstrained -file timing-route.rpt
report_timing -delay_type max -max_paths 30 -path_type full_clock_expanded -file worst-setup.rpt
report_timing -delay_type min -max_paths 30 -path_type full_clock_expanded -file worst-hold.rpt
report_utilization -hierarchical -file utilization-route.rpt
report_ram_utilization -file ram-route.rpt
report_route_status -file route-status.rpt
report_clocks -file clocks.rpt
report_exceptions -file exceptions.rpt
check_timing -verbose -file check-timing.rpt
report_drc -file drc.rpt
# Reopen the output DCP with the same independent endpoint-based audit.
set argv [list [file normalize route.dcp]]
close_design
source $common_dir/audit-checkpoint.tcl

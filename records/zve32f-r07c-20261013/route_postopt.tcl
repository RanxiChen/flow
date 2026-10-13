# R07c 100 MHz physical-only experiment. Run in a fresh directory on Alan.
# The input is the completed Explore route, never a synthesis or placed DCP.
if {$argc != 2} {error "usage: route_postopt.tcl ROUTED_DCP GATEWARE"}
set routed_dcp [lindex $argv 0]
set gateware [lindex $argv 1]
set_param general.maxThreads 4
open_checkpoint $routed_dcp
source $gateware/flow_sd_timing.tcl
report_timing_summary -delay_type min_max -report_unconstrained -file before-timing.rpt
phys_opt_design -directive AggressiveExplore
route_design -directive NoTimingRelaxation
write_checkpoint -force postopt-routed.dcp
report_timing_summary -delay_type min_max -report_unconstrained -file after-timing.rpt
report_timing -delay_type max -max_paths 30 -nworst 1 -path_type full -file worst-30-distinct.rpt
report_route_status -file route-status.rpt
report_utilization -hierarchical -file utilization-hierarchical.rpt
report_design_analysis -congestion -file congestion.rpt
check_timing -verbose -file check-timing.rpt
report_drc -file drc.rpt
flow_report_sd_timing
quit

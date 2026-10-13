# Run from a fresh evidence directory on Alan with:
# vivado -mode batch -source route_impl.tcl -tclargs GATEWARE default|explore
# Both strategies start from the exact R07c synthesis checkpoint.
if {$argc != 2} {error "usage: route_impl.tcl GATEWARE default|explore"}
set gateware [lindex $argv 0]
set strategy [lindex $argv 1]
if {$strategy ni {default explore}} {error "unknown strategy $strategy"}
set_param general.maxThreads 4
open_checkpoint $gateware/r07c-synth.dcp
source $gateware/flow_sd_timing.tcl
opt_design -directive Default
if {$strategy eq "default"} {
    place_design -directive Default
} else {
    place_design -directive Explore
    phys_opt_design -directive AggressiveExplore
}
write_checkpoint -force placed.dcp
report_timing_summary -delay_type min_max -report_unconstrained -file placed-timing.rpt
if {$strategy eq "default"} {
    route_design -directive Default
} else {
    route_design -directive Explore
}
report_timing_summary -delay_type min_max -report_unconstrained -file pre-postroute-physopt-timing.rpt
phys_opt_design -directive Default
write_checkpoint -force routed.dcp
report_route_status -file route-status.rpt
report_drc -file drc.rpt
report_utilization -hierarchical -file utilization-hierarchical.rpt
report_timing_summary -delay_type min_max -report_unconstrained -file routed-timing.rpt
report_timing -delay_type max -max_paths 30 -nworst 1 -path_type full -file worst-30-distinct.rpt
report_design_analysis -congestion -file congestion.rpt
check_timing -verbose -file check-timing.rpt
report_methodology -file methodology.rpt
flow_report_sd_timing
quit

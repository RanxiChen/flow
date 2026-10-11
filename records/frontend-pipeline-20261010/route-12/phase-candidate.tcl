set_param general.maxThreads 4
open_checkpoint input-place.dcp
if {[get_property PERIOD [get_clocks frontend_clk]] != 10.0} { error "wrong inherited clock period" }
source phase-inputs.tcl
place_design
phys_opt_design -directive AggressiveExplore
write_checkpoint -force place.dcp
report_timing_summary -delay_type min_max -report_unconstrained -file timing-place.rpt
source ooc-context.tcl
phys_opt_design -aggressive_hold_fix
route_design -directive NoTimingRelaxation
phys_opt_design -directive AggressiveExplore
write_checkpoint -force route.dcp
report_utilization -hierarchical -file utilization-route.rpt
report_timing_summary -delay_type min_max -report_unconstrained -file timing-route.rpt
report_timing -delay_type max -max_paths 20 -path_type full_clock_expanded -file worst-setup.rpt
report_timing -delay_type min -max_paths 20 -path_type full_clock_expanded -file worst-hold.rpt
report_route_status -file route-status.rpt
check_timing -verbose -file check-timing.rpt
report_drc -file drc.rpt
set wns [get_property SLACK [get_timing_paths -delay_type max -max_paths 1]]
set whs [get_property SLACK [get_timing_paths -delay_type min -max_paths 1]]
set f [open gate.json w]
puts $f "{\"top\":\"BreezeFrontendTimingHarness\",\"part\":\"xcku040-ffva1156-2-e\",\"period_ns\":10.0,\"clock_uncertainty_ns\":0.1,\"wns_ns\":$wns,\"whs_ns\":$whs,\"context\":\"explicit negative-edge physical input sampling\"}"
close $f
if {$wns < 0 || $whs < 0} { error "frontend 100 MHz timing failed: WNS=$wns WHS=$whs" }

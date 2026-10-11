set_param general.maxThreads 4
open_checkpoint input-route.dcp
if {[get_property PERIOD [get_clocks frontend_clk]] != 10.0} { error "wrong inherited clock period" }
set paths [get_timing_paths -delay_type min -slack_lesser_than 0 -max_paths 10000]
if {[llength $paths] == 0} { error "no measured short-path failures" }
set endpoints [dict create]
set plan [list]
set f [open hold-placement.csv w]
puts $f "endpoint,hold_before,setup_before,old_site,new_site"
# Placement-only: no netlist disconnect/reconnect, no added cells. Move the
# measured RAM-to-FF endpoints out of dedicated same-slice short connections.
foreach path $paths {
    set pin [get_property ENDPOINT_PIN $path]
    if {[dict exists $endpoints $pin]} { continue }
    dict set endpoints $pin 1
    set cell [get_cells -of_objects $pin]
    if {[get_property REF_NAME $cell] != "FDRE"} { error "unexpected hold endpoint type $cell" }
    set setup_path [get_timing_paths -delay_type max -to $pin -max_paths 1]
    set setup [get_property SLACK $setup_path]
    set hold [get_property SLACK $path]
    if {$setup < 1.0} { error "insufficient setup margin for endpoint movement $pin" }
    set old [get_property LOC $cell]
    if {![regexp {SLICE_X([0-9]+)Y([0-9]+)} $old unused x y]} { error "unexpected source slice $old" }
    lappend plan [list $pin $cell $hold $setup $old $x $y]
}
route_design -unroute
foreach item $plan {
    lassign $item pin cell hold setup old x y
    set chosen ""
    for {set radius 12} {$radius <= 36 && $chosen == ""} {incr radius} {
        for {set dx [expr {-$radius}]} {$dx <= $radius && $chosen == ""} {incr dx} {
            set dy [expr {$radius - abs($dx)}]
            foreach sy [lsort -unique [list $dy [expr {-$dy}]]] {
                set site [get_sites -quiet SLICE_X[expr {$x+$dx}]Y[expr {$y+$sy}]]
                if {[llength $site] != 1 || [get_property SITE_TYPE $site] != "SLICEL"} { continue }
                if {[llength [get_cells -quiet -of_objects [get_bels -of_objects $site]]] != 0} { continue }
                set chosen $site
                break
            }
        }
    }
    if {$chosen == ""} { error "no empty nearby SLICEL for $cell" }
    set_property IS_LOC_FIXED false $cell
    set_property IS_BEL_FIXED false $cell
    unplace_cell $cell
    place_cell $cell $chosen/AFF
    puts $f "$pin,$hold,$setup,$old,$chosen"
}
close $f
puts "PLACEMENT_HOLD_FIX moved=[dict size $endpoints]; netlist/state/constraints unchanged"
route_design -directive NoTimingRelaxation
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
puts $f "{\"top\":\"BreezeFrontendTimingHarness\",\"part\":\"xcku040-ffva1156-2-e\",\"period_ns\":10.0,\"clock_uncertainty_ns\":0.1,\"wns_ns\":$wns,\"whs_ns\":$whs,\"context\":\"negative-edge physical input sampling; measured endpoint placement repair\"}"
close $f
if {$wns < 0 || $whs < 0} { error "frontend 100 MHz timing failed: WNS=$wns WHS=$whs" }

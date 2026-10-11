set_param general.maxThreads 4
open_checkpoint input-route.dcp
if {[get_property PERIOD [get_clocks frontend_clk]] != 10.0} { error "wrong inherited clock period" }
# Preserve placed frontend and physical environment; add only identity LUT1s
# on measured failing endpoint nets with ample setup slack.
set placed [get_cells -hierarchical -filter {IS_PRIMITIVE && LOC != "" && REF_NAME !~ BUFG*}]
set_property IS_LOC_FIXED true $placed
set_property IS_BEL_FIXED true $placed
set_msg_config -id {Netlist 29-48} -new_severity ERROR
set paths [get_timing_paths -delay_type min -slack_lesser_than 0 -max_paths 10000]
if {[llength $paths] == 0} { error "no measured hold failures to repair" }
set f [open hold-identity.csv w]
puts $f "endpoint,hold_before,setup_before,buffer"
set index 0
set endpoints [dict create]
foreach path $paths {
    set pin [get_property ENDPOINT_PIN $path]
    if {[dict exists $endpoints $pin]} { continue }
    dict set endpoints $pin 1
    set setup_path [get_timing_paths -delay_type max -to $pin -max_paths 1]
    set setup [get_property SLACK $setup_path]
    set hold [get_property SLACK $path]
    if {$setup < 0.5} { error "hold endpoint $pin lacks identity-buffer setup margin: $setup" }
    set net [get_nets -of_objects $pin]
    if {[llength $net] != 1} { error "missing endpoint net $pin" }
    disconnect_net -net $net -objects $pin
    set name physical_hold_identity_$index
    set cell [create_cell -reference LUT1 $name]
    set_property INIT 2'h2 $cell
    connect_net -hier -net $net -objects [get_pins $name/I0]
    set delayed [create_net ${name}_q]
    connect_net -hier -net $delayed -objects [concat [get_pins $name/O] $pin]
    if {[llength [get_nets -of_objects $pin]] == 0} { error "disconnected repaired endpoint $pin" }
    puts $f "$pin,$hold,$setup,$name"
    incr index
}
close $f
puts "IDENTITY_HOLD_FIX added=$index unchanged_cycles=true"
place_design -directive Quick
write_checkpoint -force hold-place.dcp
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
puts $f "{\"top\":\"BreezeFrontendTimingHarness\",\"part\":\"xcku040-ffva1156-2-e\",\"period_ns\":10.0,\"clock_uncertainty_ns\":0.1,\"wns_ns\":$wns,\"whs_ns\":$whs,\"context\":\"negative-edge physical input sampling; measured endpoint LUT1 hold repair\"}"
close $f
if {$wns < 0 || $whs < 0} { error "frontend 100 MHz timing failed: WNS=$wns WHS=$whs" }

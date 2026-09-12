# SD PHY uses sys-clocked IO flops and clock enables, not an SD clock domain.
# Limit transport delay only on the actual SD IO paths. Board/protocol budget
# and its assumptions are documented in sd-timing.md. Never select all Q pins.
set flow_sd_in [get_ports {sdcard_cmd sdcard_data[*]}]
set flow_sd_out [get_ports {sdcard_clk sdcard_cmd sdcard_data[*]}]
if {[llength $flow_sd_in] != 5 || [llength $flow_sd_out] != 6} {
    error "Unexpected SD ports; refusing broad timing exceptions"
}
set flow_sd_rx [get_cells -of_objects [all_fanout -flat -endpoints_only -from $flow_sd_in] -filter {IS_SEQUENTIAL}]
set flow_sd_tx [get_cells -of_objects [all_fanin -flat -startpoints_only -to $flow_sd_out] -filter {IS_SEQUENTIAL}]
if {[llength $flow_sd_rx] != 5 || [llength $flow_sd_tx] < 6 || [llength $flow_sd_tx] > 16} {
    error "Unexpected SD IO register topology: RX=$flow_sd_rx TX=$flow_sd_tx"
}
# Whole sequential cells are valid timing start/end points. Using Q pins as
# exception startpoints can segment timing and hide unrelated internal paths.
set_max_delay -datapath_only 10.0 -from $flow_sd_in -to $flow_sd_rx
set_max_delay -datapath_only 10.0 -from $flow_sd_tx -to $flow_sd_out
puts "FLOW_SD_TIMING RX=[llength $flow_sd_rx] TX=[llength $flow_sd_tx] IO_BUDGET_NS=10"
proc flow_report_sd_timing {} {
    global flow_sd_in flow_sd_out flow_sd_rx flow_sd_tx
    report_timing -from $flow_sd_in -to $flow_sd_rx -max_paths 20 -file sd_input_paths.rpt
    report_timing -from $flow_sd_tx -to $flow_sd_out -max_paths 30 -file sd_output_paths.rpt
    report_timing -delay_type min -from $flow_sd_tx -to $flow_sd_out -max_paths 30 -file sd_output_min_paths.rpt
    report_exceptions -file sd_timing_exceptions.rpt
    set bad_in [get_timing_paths -from $flow_sd_in -to $flow_sd_rx -slack_lesser_than 0 -max_paths 1]
    set bad_out [get_timing_paths -from $flow_sd_tx -to $flow_sd_out -slack_lesser_than 0 -max_paths 1]
    if {[llength $bad_in] || [llength $bad_out]} {error "SD IO transport budget failed"}
    puts "FLOW_SD_IO_BUDGET_PASS (board assumptions still require confirmation)"
}

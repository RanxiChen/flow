set_param general.maxThreads 4
set rtl_dir [file normalize [lindex $argv 0]]
set common_dir [file normalize [lindex $argv 1]]
set part xcku040-ffva1156-2-e
create_project -in_memory -part $part
set_property verilog_define SYNTHESIS [current_fileset]
read_verilog -sv [lsort [glob $rtl_dir/*.sv]]
read_xdc $common_dir/synth.xdc
synth_design -top BreezeBtbTimingHarness -mode out_of_context -part $part -flatten_hierarchy rebuilt
set bufg [get_cells -hierarchical -filter {REF_NAME == BUFGCE}]
if {[llength $bufg] != 1} {error "expected exactly one BUFGCE"}
create_clock -name experiment_clk -period 5.000 [get_pins -of_objects $bufg -filter {REF_PIN_NAME == O}]
set_clock_uncertainty 0.100 [get_clocks experiment_clk]
# Local synchronous fixture interface; not board IO. Internal paths stay 5 ns.
set_input_delay -clock experiment_clk -max 0.500 [get_ports -filter {DIRECTION == IN && NAME != clock}]
set_input_delay -clock experiment_clk -min 0.500 [get_ports -filter {DIRECTION == IN && NAME != clock}]
set_output_delay -clock experiment_clk -max 0.500 [all_outputs]
set_output_delay -clock experiment_clk -min 0.500 [all_outputs]
write_checkpoint -force synth.dcp
report_utilization -hierarchical -file utilization-synth.rpt
report_ram_utilization -file ram-synth.rpt
report_timing_summary -delay_type min_max -report_unconstrained -file timing-synth.rpt
opt_design
place_design
phys_opt_design -directive AggressiveExplore
source $common_dir/fixture-reference.tcl
source $common_dir/ooc-context.tcl
write_xdc -force effective.xdc
write_checkpoint -force place.dcp
report_timing_summary -delay_type min_max -report_unconstrained -file timing-place.rpt
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
set seq [get_cells -hierarchical -filter {IS_SEQUENTIAL == 1}]
report_timing -from $seq -to $seq -delay_type max -max_paths 50 -path_type full_clock_expanded -file internal-setup.rpt
report_timing -from $seq -to $seq -delay_type min -max_paths 30 -path_type full_clock_expanded -file internal-hold.rpt
# Query result endpoints are fixture registers, not the external ports.
set results [filter $seq {NAME =~ *io_result_*}]
if {[llength $results] == 0} {error "query-result capture registers missing"}
report_timing -to $results -delay_type max -max_paths 30 -path_type full_clock_expanded -file query-result-setup.rpt
report_timing -from $seq -to $seq -delay_type max -max_paths 300 -path_type summary -file internal-top300.rpt
set f [open sequential-cells.txt w]
foreach c $seq {puts $f "$c,[get_property REF_NAME $c]"}
close $f
set f [open memory-cells.txt w]
foreach c [get_cells -hierarchical -filter {REF_NAME =~ RAM* || REF_NAME =~ URAM*}] {
  puts $f "$c,[get_property REF_NAME $c]"
}
close $f
set wns [get_timing_paths -delay_type max -max_paths 1]
set whs [get_timing_paths -delay_type min -max_paths 1]
set iwns [get_timing_paths -from $seq -to $seq -delay_type max -max_paths 1]
set iwhs [get_timing_paths -from $seq -to $seq -delay_type min -max_paths 1]
set qwns [get_timing_paths -to $results -delay_type max -max_paths 1]
foreach p [list $wns $whs $iwns $iwhs $qwns] {if {[llength $p] != 1} {error "missing measured path"}}
set f [open result.json w]
puts $f "{\"part\":\"$part\",\"period_ns\":5.0,\"uncertainty_ns\":0.1,\"wns_ns\":[get_property SLACK $wns],\"whs_ns\":[get_property SLACK $whs],\"internal_wns_ns\":[get_property SLACK $iwns],\"internal_whs_ns\":[get_property SLACK $iwhs],\"query_result_wns_ns\":[get_property SLACK $qwns],\"fixture_io_max_ns\":0.5,\"fixture_io_min_ns\":0.5,\"result_registers\":[llength $results],\"stage\":\"route_design NoTimingRelaxation\"}"
close $f
puts "MEASUREMENT_COMPLETE WNS=[get_property SLACK $wns] WHS=[get_property SLACK $whs] QUERY_WNS=[get_property SLACK $qwns]"

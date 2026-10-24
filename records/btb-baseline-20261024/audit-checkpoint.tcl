set_param general.maxThreads 4
set checkpoint [file normalize [lindex $argv 0]]
open_checkpoint $checkpoint
set seq [get_cells -hierarchical -filter {IS_SEQUENTIAL == 1}]
set result_ports [get_ports -filter {DIRECTION == OUT && NAME =~ io_result*}]
set results [lsort -unique [get_cells -quiet -of_objects [all_fanin -flat -startpoints_only -to $result_ports] -filter {IS_SEQUENTIAL == 1}]]
if {[llength $results] < 130} {error "query-result endpoints incomplete"}
set query_ports [get_ports -filter {DIRECTION == IN && NAME =~ io_query*}]
set query_regs [lsort -unique [get_cells -quiet -of_objects [all_fanout -flat -endpoints_only -from $query_ports] -filter {IS_SEQUENTIAL == 1}]]
if {[llength $query_regs] < 64} {error "query input registers incomplete"}
set update_state [filter $seq {NAME =~ *btb*/update_* || NAME =~ *btb*/valid_* || NAME =~ *btb*/rr_* || NAME =~ *btb*/writes/*}]
if {[llength $update_state] == 0} {error "BTB update state not found"}
report_timing_summary -delay_type min_max -report_unconstrained -file timing-route.rpt
report_timing -from $seq -to $seq -delay_type max -max_paths 50 -path_type full_clock_expanded -file internal-setup.rpt
report_timing -from $seq -to $seq -delay_type min -max_paths 30 -path_type full_clock_expanded -file internal-hold.rpt
report_timing -from $query_regs -to $seq -delay_type max -max_paths 30 -path_type full_clock_expanded -file query-f0-setup.rpt
report_timing -to $results -delay_type max -max_paths 30 -path_type full_clock_expanded -file query-result-setup.rpt
report_timing -from $seq -to $update_state -delay_type max -max_paths 30 -path_type full_clock_expanded -file update-state-setup.rpt
set wns [get_timing_paths -delay_type max -max_paths 1]
set whs [get_timing_paths -delay_type min -max_paths 1]
set iwns [get_timing_paths -from $seq -to $seq -delay_type max -max_paths 1]
set iwhs [get_timing_paths -from $seq -to $seq -delay_type min -max_paths 1]
set f0 [get_timing_paths -from $query_regs -to $seq -delay_type max -max_paths 1]
set q [get_timing_paths -to $results -delay_type max -max_paths 1]
set u [get_timing_paths -from $seq -to $update_state -delay_type max -max_paths 1]
set f [open endpoint-groups.txt w]
foreach group [list $query_regs $results $update_state] {
  puts $f "GROUP"
  foreach c $group {puts $f "$c,[get_property REF_NAME $c]"}
}
close $f
set f [open result.json w]
puts $f "{\"part\":\"xcku040-ffva1156-2-e\",\"period_ns\":5.0,\"uncertainty_ns\":0.1,\"wns_ns\":[get_property SLACK $wns],\"whs_ns\":[get_property SLACK $whs],\"internal_wns_ns\":[get_property SLACK $iwns],\"internal_whs_ns\":[get_property SLACK $iwhs],\"query_f0_wns_ns\":[get_property SLACK $f0],\"query_result_wns_ns\":[get_property SLACK $q],\"update_state_wns_ns\":[get_property SLACK $u],\"result_registers\":[llength $results],\"query_input_registers\":[llength $query_regs],\"update_registers\":[llength $update_state],\"checkpoint\":\"$checkpoint\",\"fixture_io_max_ns\":0.5,\"fixture_io_min_ns\":0.5}"
close $f
puts "AUDIT_COMPLETE WNS=[get_property SLACK $wns] WHS=[get_property SLACK $whs] F0=[get_property SLACK $f0] QUERY=[get_property SLACK $q] UPDATE=[get_property SLACK $u]"

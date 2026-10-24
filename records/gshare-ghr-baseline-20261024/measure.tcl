set seq [get_cells -hierarchical -filter {IS_SEQUENTIAL == 1}]
set f [open sequential-cells.txt w]
foreach c $seq {puts $f "$c,[get_property REF_NAME $c]"}
close $f
set result_ports [get_ports -filter {DIRECTION == OUT && NAME =~ io_result*}]
set results [lsort -unique [get_cells -quiet -of_objects [all_fanin -flat -startpoints_only -to $result_ports] -filter {IS_SEQUENTIAL == 1}]]
set query_regs [lsort -unique [get_cells -quiet -of_objects [all_fanout -flat -endpoints_only -from [get_ports -filter {DIRECTION == IN && NAME =~ io_query*}]] -filter {IS_SEQUENTIAL == 1}]]
set forecast [filter $seq {NAME =~ *forecastHistory*}]
set history [filter $seq {NAME =~ *f1History*}]
foreach {label cells} [list f1-forecast $forecast feedback-history $history f1-result $results f0-input $query_regs] {
  if {![llength $cells]} {error "missing group $label"}
  if {$label == "f0-input"} {
    report_timing -from $cells -to $seq -delay_type max -max_paths 30 -path_type full_clock_expanded -file $label-setup.rpt
  } else {
    report_timing -to $cells -delay_type max -max_paths 30 -path_type full_clock_expanded -file $label-setup.rpt
  }
}
report_timing -from $seq -to $seq -delay_type max -max_paths 300 -nworst 2 -path_type summary -file internal-top300.rpt
set wns [get_timing_paths -delay_type max -max_paths 1]
set whs [get_timing_paths -delay_type min -max_paths 1]
set q [get_timing_paths -to $results -delay_type max -max_paths 1]
set f1 [get_timing_paths -to $forecast -delay_type max -max_paths 1]
set fb [get_timing_paths -to $history -delay_type max -max_paths 1]
set f0 [get_timing_paths -from $query_regs -to $seq -delay_type max -max_paths 1]
set f [open result.json w]
puts $f "{\"part\":\"xcku040-ffva1156-2-e\",\"period_ns\":5.0,\"wns_ns\":[get_property SLACK $wns],\"whs_ns\":[get_property SLACK $whs],\"f1_forecast_wns_ns\":[get_property SLACK $f1],\"feedback_history_wns_ns\":[get_property SLACK $fb],\"f1_result_wns_ns\":[get_property SLACK $q],\"f0_input_wns_ns\":[get_property SLACK $f0]}"
close $f
puts "MEASUREMENT_COMPLETE WNS=[get_property SLACK $wns] WHS=[get_property SLACK $whs]"

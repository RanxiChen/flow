set_param general.maxThreads 4
open_checkpoint [file normalize [lindex $argv 0]]
set seq [get_cells -hierarchical -filter {IS_SEQUENTIAL == 1}]
set forecast [filter $seq {NAME =~ *forecastHistory*}]
set init [filter $seq {NAME =~ *pht/init_reg* || NAME =~ *pht/init_1_reg* || NAME =~ *pht/init_2_reg*}]
set address [filter $seq {NAME =~ *pht/ram_*/_R0_addr_d0_reg* || NAME =~ *pht/ram_*/_R0_en_d0_reg*}]
set query [filter $seq {NAME =~ *pht/index_reg* || NAME =~ *pht/index_1_reg* || NAME =~ *pht/index_2_reg* || NAME =~ *pht/available* || NAME =~ *pht/bypass_reg* || NAME =~ *pht/bypass_1_reg* || NAME =~ *pht/bypass_2_reg*}]
set prediction [lsort -unique [concat $forecast $init $address $query [filter $seq {NAME =~ *f1History* || NAME =~ *f1Metadata* || NAME =~ *f1Pending*}]]]
set f [open feedback-groups.txt w]
foreach {label cells} [list init $init address $address query $query forecast $forecast prediction $prediction] {
  puts $f "$label [llength $cells]"
  foreach c $cells {puts $f "$c,[get_property REF_NAME $c]"}
  if {![llength $cells]} {error "empty endpoint group $label"}
}
close $f
foreach {label cells} [list feedback-init $init feedback-address $address feedback-query $query prediction $prediction] {
  report_timing -to $cells -delay_type max -max_paths 20 -path_type full_clock_expanded -file $label-setup.rpt
  report_timing -from $forecast -to $cells -delay_type max -max_paths 10 -path_type full_clock_expanded -file forecast-to-$label-setup.rpt
  report_timing -to $cells -delay_type min -max_paths 10 -path_type full_clock_expanded -file $label-hold.rpt
}
set f [open feedback-result.json w]
puts $f "\{"
set sep ""
foreach {label cells} [list feedback_init $init feedback_address $address feedback_query $query prediction $prediction] {
  set p [get_timing_paths -to $cells -delay_type max -max_paths 1]
  set h [get_timing_paths -to $cells -delay_type min -max_paths 1]
  set b [get_timing_paths -from $forecast -to $cells -delay_type max -max_paths 1]
  puts $f "$sep\"${label}_wns_ns\":[get_property SLACK $p],\"${label}_whs_ns\":[get_property SLACK $h],\"forecast_to_${label}_wns_ns\":[get_property SLACK $b]"
  set sep ","
}
puts $f "\}"
close $f
puts "FEEDBACK_AUDIT_COMPLETE"

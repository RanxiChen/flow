set seq [get_cells -hierarchical -filter {IS_SEQUENTIAL == 1}]
report_timing -from $seq -to $seq -delay_type max -max_paths 30 -path_type full_clock_expanded -file internal-setup.rpt
report_timing -from $seq -to $seq -delay_type min -max_paths 30 -path_type full_clock_expanded -file internal-hold.rpt
set csr [filter $seq {NAME =~ *experiment/csr/*}]
set checker [filter $seq {NAME =~ *experiment/checker*/*}]
if {[llength $csr] == 0 || [llength $checker] == 0} {error "missing CSR or checker registers"}
report_timing -from $csr -to $csr -delay_type max -max_paths 20 -path_type full_clock_expanded -file csr-predecode-setup.rpt
report_timing -to $checker -delay_type max -max_paths 30 -path_type full_clock_expanded -file checker-setup.rpt
set f [open sequential-cells.txt w]
foreach c $seq {puts $f "$c,[get_property REF_NAME $c]"}
close $f
set max_path [get_timing_paths -delay_type max -max_paths 1]
set min_path [get_timing_paths -delay_type min -max_paths 1]
set imax [get_timing_paths -from $seq -to $seq -delay_type max -max_paths 1]
set imin [get_timing_paths -from $seq -to $seq -delay_type min -max_paths 1]
foreach p [list $max_path $min_path $imax $imin] {if {[llength $p] != 1} {error "missing timing path"}}
set f [open result.json w]
puts $f "{\"part\":\"$part\",\"period_ns\":5.0,\"uncertainty_ns\":0.1,\"wns_ns\":[get_property SLACK $max_path],\"whs_ns\":[get_property SLACK $min_path],\"internal_wns_ns\":[get_property SLACK $imax],\"internal_whs_ns\":[get_property SLACK $imin],\"csr_registers\":[llength $csr],\"checker_registers\":[llength $checker],\"stage\":\"route_design NoTimingRelaxation\",\"fixture\":\"BUFG output clock, 0.5/0.2 ns boundary-clock-referenced IO; physical virtual pins\"}"
close $f
puts "MEASUREMENT_COMPLETE WNS=[get_property SLACK $max_path] WHS=[get_property SLACK $min_path]"

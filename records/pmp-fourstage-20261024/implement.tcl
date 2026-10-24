set_param general.maxThreads 4
set rtl_dir [file normalize [lindex $argv 0]]
set common_dir [file normalize [lindex $argv 1]]
set part xcku040-ffva1156-2-e
create_project -in_memory -part $part
set_property verilog_define SYNTHESIS [current_fileset]
read_verilog -sv [lsort [glob $rtl_dir/*.sv]]
create_clock -name experiment_clk -period 5.000 [get_ports clock]
set_clock_uncertainty 0.100 [get_clocks experiment_clk]
synth_design -top BreezePmpFourStageHarness -mode out_of_context -part $part -flatten_hierarchy rebuilt
set bufgs [get_cells -hierarchical -filter {REF_NAME == BUFGCE}]
if {[llength $bufgs] != 1} {error "expected one BUFGCE"}
create_clock -name experiment_clk -period 5.000 [get_pins -of_objects $bufgs -filter {REF_PIN_NAME == O}]
set_clock_uncertainty 0.100 [get_clocks experiment_clk]
set data_inputs [get_ports -filter {DIRECTION == IN && NAME != clock}]
set_input_delay -clock experiment_clk -max 0.500 $data_inputs
set_input_delay -clock experiment_clk -min 0.200 $data_inputs
set_output_delay -clock experiment_clk -max 0.500 [all_outputs]
set_output_delay -clock experiment_clk -min 0.200 [all_outputs]
write_checkpoint -force synth.dcp
report_utilization -hierarchical -file utilization-synth.rpt
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
report_route_status -file route-status.rpt
report_clocks -file clocks.rpt
report_exceptions -file exceptions.rpt
check_timing -verbose -file check-timing.rpt
report_drc -file drc.rpt
set seq [get_cells -hierarchical -filter {IS_SEQUENTIAL == 1}]
report_timing -from $seq -to $seq -delay_type max -max_paths 30 -path_type full_clock_expanded -file internal-setup.rpt
report_timing -from $seq -to $seq -delay_type min -max_paths 30 -path_type full_clock_expanded -file internal-hold.rpt
set csr [filter $seq {NAME =~ *experiment/csr/*}]
set checker [filter $seq {NAME =~ *experiment/checker/*}]
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

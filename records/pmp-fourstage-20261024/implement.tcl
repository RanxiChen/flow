set_param general.maxThreads 4
set rtl_dir [file normalize [lindex $argv 0]]
set common_dir [file normalize [lindex $argv 1]]
set part xcku040-ffva1156-2-e
create_project -in_memory -part $part
set_property verilog_define SYNTHESIS [current_fileset]
read_verilog -sv [lsort [glob $rtl_dir/*.sv]]
read_xdc $common_dir/synth.xdc
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
source $common_dir/summarize.tcl

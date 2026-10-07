# Default-config subset OOC evidence, not a full RVV/backend area claim.
set source_dir [file normalize [lindex $argv 0]]
set report_dir [file normalize [lindex $argv 1]]
file mkdir $report_dir
set_param general.maxThreads 4
read_verilog -sv [glob $source_dir/*.sv]
read_xdc [file join [file dirname [info script]] clock.xdc]
synth_design -top RvvCoprocessor -part xcku040-ffva1156-2-e -mode out_of_context
report_utilization -hierarchical -file $report_dir/utilization-hierarchical.rpt
report_utilization -file $report_dir/utilization.rpt
report_timing_summary -file $report_dir/timing-summary.rpt
report_timing -max_paths 10 -path_type full -file $report_dir/worst-paths.rpt
write_checkpoint -force $report_dir/rvv-r03-synth.dcp

set synth_dcp [file normalize [lindex $argv 0]]
set report_dir [file normalize [lindex $argv 1]]
file mkdir $report_dir
set_param general.maxThreads 4
open_checkpoint $synth_dcp
opt_design
write_checkpoint -force $report_dir/post-opt.dcp
place_design
report_timing_summary -file $report_dir/post-place-timing.rpt
write_checkpoint -force $report_dir/post-place.dcp
route_design
report_route_status -file $report_dir/route-status.rpt
report_utilization -hierarchical -file $report_dir/utilization-hierarchical.rpt
report_utilization -file $report_dir/utilization.rpt
report_timing_summary -file $report_dir/timing-summary.rpt
report_timing -max_paths 10 -path_type full -file $report_dir/worst-paths.rpt
write_checkpoint -force $report_dir/rvv-r03-route.dcp

set_param general.maxThreads 4
open_checkpoint input-route.dcp
report_timing_summary -delay_type min_max -file timing-before.rpt
phys_opt_design -insert_negative_edge_ffs
report_timing_summary -delay_type min_max -file timing-after.rpt
report_utilization -hierarchical -file utilization-after.rpt
write_checkpoint -force after.dcp

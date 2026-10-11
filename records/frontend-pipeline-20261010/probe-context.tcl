open_checkpoint /home/chen/FUN/flow-runs/frontend-pipeline-20261010/route-03/place.dcp
source /home/chen/FUN/flow-frontend-pipeline-20261010/ooc-context.tcl
set port [get_ports io_context_pmpRangesValid]
puts "PROBE_ANCHOR=[get_property HD.PARTPIN_LOCS $port]"
route_design -nets [get_nets -of_objects $port]
report_timing -from $port -delay_type min -max_paths 1 -file probe-port-hold.rpt

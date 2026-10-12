set_param general.maxThreads 4
open_checkpoint xilinx_kcu105_route.dcp
report_bus_skew -file bus-skew.rpt
report_clock_interaction -file clock-interaction.rpt
quit

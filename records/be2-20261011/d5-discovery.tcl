set_param general.maxThreads 2
open_checkpoint [lindex $argv 0]
set cells [get_cells -hierarchical -filter {NAME =~ *cluster/data/* && REF_NAME =~ RAMB*}]
puts "D5_DISCOVERY_DATA [llength $cells]"
if {[llength $cells]} {report_property -all [lindex $cells 0]}
set modules [get_cells -hierarchical -filter {NAME =~ *cluster/data || NAME =~ *cluster/l2/data}]
foreach c $modules {report_property -all $c}
exit

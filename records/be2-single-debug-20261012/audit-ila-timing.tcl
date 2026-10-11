set_param general.maxThreads 2
open_checkpoint [lindex $argv 0]
puts [help report_bus_skew]
report_bus_skew -file ila-bus-skew.rpt
report_exceptions -file debug-final-exceptions.rpt
report_clocks -file debug-final-clocks.rpt
set output [open debug-core-properties.txt w]
foreach core [get_debug_cores] {
    puts $output "CORE=$core"
    foreach name [list_property $core] {
        puts $output "$name=[get_property $name $core]"
    }
}
close $output
exit

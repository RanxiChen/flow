# vivado -mode batch -source flight_read.tcl -tclargs list
# vivado -mode batch -source flight_read.tcl -tclargs dump ID ABS_FRESH_CSV
# vivado -mode batch -source flight_read.tcl -tclargs capture ABS_FRESH_CSV
if {$argc == 1 && [lindex $argv 0] eq "list"} {
    set action list
} elseif {$argc == 3 && [lindex $argv 0] eq "dump"} {
    set action dump
} elseif {$argc == 2 && [lindex $argv 0] eq "capture"} {
    set action capture
} else {error "Expected: list | dump ID ABS_FRESH_CSV | capture ABS_FRESH_CSV"}
source [file join [file dirname [info script]] fase_jtag.tcl]
source [file join [file dirname [info script]] flight_recorder.tcl]
open_hw_manager
connect_hw_server -url localhost:3121
set targets [get_hw_targets *210308A7B107*]
if {[llength $targets] != 1} {error "Expected the KCU105 Digilent 210308A7B107 target"}
current_hw_target [lindex $targets 0]
set_property PARAM.FREQUENCY 10000000 [current_hw_target]
open_hw_target -jtag_mode on
fase_select
set rc [catch {
    switch $action {
        list {flight_list}
        dump {puts "SAVED [flight_dump [lindex $argv 2] [lindex $argv 1]]"}
        capture {puts "SAVED [flight_capture [lindex $argv 1]]"}
    }
} message options]
close_hw_target
disconnect_hw_server
close_hw_manager
if {$rc} {return -options $options $message}
exit

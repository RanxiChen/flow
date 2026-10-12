# Load the timing-gated CSR-fix bit into the already identified KU040, then
# immediately capture its matching ILA. No configuration-flash programming.
if {[llength $argv] != 4} {
    error "Usage: program-and-capture.tcl BIT LTX FRESH_OUTPUT_PREFIX HW_SERVER_URL"
}
set bit [file normalize [lindex $argv 0]]
set probes [file normalize [lindex $argv 1]]
set output [file normalize [lindex $argv 2]]
set server [lindex $argv 3]
foreach path [list $bit $probes] {
    if {![file isfile $path]} {error "Missing verified product: $path"}
}
foreach extension {ila csv vcd} {
    if {[file exists "$output.$extension"]} {error "Output already exists: $output.$extension"}
}
load_features labtools
open_hw_manager
connect_hw_server -url $server
set targets [get_hw_targets -quiet]
if {[llength $targets] != 1 || ![string match */Digilent/210308A7B107 [lindex $targets 0]]} {
    error "Expected the previously captured Digilent target 210308A7B107; found $targets"
}
current_hw_target [lindex $targets 0]
set_property PARAM.FREQUENCY 1000000 [current_hw_target]
open_hw_target
set devices [get_hw_devices -quiet xcku040*]
if {[llength $devices] != 1} {error "Expected one KU040; found $devices"}
set device [lindex $devices 0]
current_hw_device $device
puts "BE2_PROGRAM_BIT=$bit"
set_property PROGRAM.FILE $bit $device
program_hw_devices $device
set_property PROBES.FILE $probes $device
refresh_hw_device $device
set ilas [get_hw_ilas -quiet -of_objects $device -filter {CELL_NAME =~ *breeze_ila*}]
if {[llength $ilas] != 1} {error "Expected matching breeze_ila after programming"}
set ila [lindex $ilas 0]
current_hw_ila $ila
reset_hw_ila $ila
set_property CONTROL.DATA_DEPTH 4096 $ila
set_property CONTROL.TRIGGER_POSITION 0 $ila
run_hw_ila -trigger_now $ila
wait_on_hw_ila -timeout 0.5 $ila
set data [upload_hw_ila_data $ila]
write_hw_ila_data "$output.ila" $data
write_hw_ila_data -csv_file "$output.csv" $data
write_hw_ila_data -vcd_file "$output.vcd" $data
puts "BE2_PROGRAM_CAPTURE=$output"
close_hw_target
disconnect_hw_server
close_hw_manager
exit

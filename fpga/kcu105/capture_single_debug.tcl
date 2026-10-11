# Read ILA after the matching debug bitstream has been loaded by the user.
# No FPGA programming, CPU reset, VIO writes or bus transactions.
# source with argv: MATCHING_LTX ABSOLUTE_FRESH_OUTPUT_PREFIX [HW_SERVER_URL]
if {[llength $argv] < 2 || [llength $argv] > 3} {
    error "Usage: capture_single_debug.tcl MATCHING_LTX FRESH_OUTPUT_PREFIX ?HW_SERVER_URL?"
}
set probes [file normalize [lindex $argv 0]]
set output [file normalize [lindex $argv 1]]
set server [expr {[llength $argv] == 3 ? [lindex $argv 2] : "localhost:3121"}]
if {![file isfile $probes]} {error "Matching LTX file missing"}
foreach extension {ila csv vcd} {
    if {[file exists "$output.$extension"]} {error "Capture output already exists: $output.$extension"}
}
load_features labtools
open_hw_manager
connect_hw_server -url $server
set targets [get_hw_targets -quiet]
if {[llength $targets] != 1} {error "Expected exactly one JTAG target; found $targets"}
current_hw_target [lindex $targets 0]
set_property PARAM.FREQUENCY 1000000 [current_hw_target]
open_hw_target
set devices [get_hw_devices -quiet xcku040*]
if {[llength $devices] != 1} {error "Expected one KU040 device; found $devices"}
set device [lindex $devices 0]
current_hw_device $device
set_property PROBES.FILE $probes $device
refresh_hw_device $device
set ilas [get_hw_ilas -quiet -of_objects $device -filter {CELL_NAME =~ *breeze_ila*}]
if {[llength $ilas] != 1} {error "Matching breeze_ila not detected; check programmed bit/LTX/clock"}
set ila [lindex $ilas 0]
current_hw_ila $ila
reset_hw_ila $ila
set_property CONTROL.DATA_DEPTH 4096 $ila
set_property CONTROL.TRIGGER_POSITION 0 $ila
run_hw_ila -trigger_now $ila
# Vivado2022.2 timeout is in minutes; .5 is thirty seconds.
wait_on_hw_ila -timeout 0.5 $ila
set data [upload_hw_ila_data $ila]
write_hw_ila_data "$output.ila" $data
write_hw_ila_data -csv_file "$output.csv" $data
write_hw_ila_data -vcd_file "$output.vcd" $data
puts "BE2_ILA_CAPTURE=$output"
close_hw_target
disconnect_hw_server
close_hw_manager
exit

# Usage:
#   vivado -mode batch -source export_capture.tcl \
#     -tclargs /abs/capture-directory ?localhost:3121?

if {$argc < 1 || $argc > 2} {
    error "usage: export_capture.tcl OUTPUT_DIRECTORY ?HW_SERVER_URL?"
}
set output_dir [file normalize [lindex $argv 0]]
set server_url [expr {$argc == 2 ? [lindex $argv 1] : "localhost:3121"}]
file mkdir $output_dir

open_hw_manager
connect_hw_server -url $server_url
open_hw_target
set devices [get_hw_devices -quiet -filter {PART =~ "xcku040*"}]
if {[llength $devices] != 1} {
    error "expected exactly one xcku040 device, found [llength $devices]: $devices"
}
set device [lindex $devices 0]
current_hw_device $device
refresh_hw_device $device

set exported 0
foreach ila [get_hw_ilas -of_objects $device] {
    set cell [get_property CELL_NAME $ila]
    set stem [string map {/ _ \\ _ : _} $cell]
    set data [upload_hw_ila_data $ila]
    write_hw_ila_data -force -csv_file [file join $output_dir ${stem}.csv] $data
    write_hw_ila_data -force [file join $output_dir ${stem}.ila] $data
    incr exported
}
if {$exported != 2} {
    error "expected two ILA captures, exported $exported"
}
puts "FLOW_PCIE_ONLY_EXPORTED count=$exported directory=$output_dir"

# Usage:
#   vivado -mode batch -source program_and_arm.tcl \
#     -tclargs /abs/pcie_only.bit /abs/pcie_only.ltx ?localhost:3121?
#
# This script programs the PCIe-only image and arms both ILAs.  Run it while
# the host is still powered off, then power the host on.  It intentionally
# does not start or stop any host machine.

if {$argc < 2 || $argc > 3} {
    error "usage: program_and_arm.tcl BIT LTX ?HW_SERVER_URL?"
}
set bit_file [file normalize [lindex $argv 0]]
set ltx_file [file normalize [lindex $argv 1]]
set server_url [expr {$argc == 3 ? [lindex $argv 2] : "localhost:3121"}]
foreach path [list $bit_file $ltx_file] {
    if {![file isfile $path]} { error "missing file: $path" }
}

open_hw_manager
connect_hw_server -url $server_url
open_hw_target
set devices [get_hw_devices -quiet -filter {PART =~ "xcku040*"}]
if {[llength $devices] != 1} {
    error "expected exactly one xcku040 device, found [llength $devices]: $devices"
}
set device [lindex $devices 0]
current_hw_device $device
set_property PROGRAM.FILE $bit_file $device
set_property PROBES.FILE $ltx_file $device
set_property FULL_PROBES.FILE $ltx_file $device
program_hw_devices $device
refresh_hw_device $device

set boot_ila [get_hw_ilas -quiet -of_objects $device -filter {CELL_NAME =~ "*boot_ila*"}]
set link_ila [get_hw_ilas -quiet -of_objects $device -filter {CELL_NAME =~ "*link_ila*"}]
if {[llength $boot_ila] != 1 || [llength $link_ila] != 1} {
    error "ILA discovery mismatch: boot=$boot_ila link=$link_ila"
}

# Keep half the samples before the trigger.  The boot ILA triggers when PERST#
# rises; the link ILA triggers when its synchronized PERST# copy rises.
set_property CONTROL.TRIGGER_POSITION 2048 $boot_ila
set_property CONTROL.TRIGGER_POSITION 4096 $link_ila
set boot_perst [get_hw_probes -quiet -of_objects $boot_ila -filter {NAME =~ "*probe0"}]
set link_perst [get_hw_probes -quiet -of_objects $link_ila -filter {NAME =~ "*probe5"}]
if {[llength $boot_perst] != 1 || [llength $link_perst] != 1} {
    error "PERST probe discovery mismatch: boot=$boot_perst link=$link_perst"
}
set_property TRIGGER_COMPARE_VALUE eq1'bR $boot_perst
set_property TRIGGER_COMPARE_VALUE eq1'bR $link_perst
run_hw_ila $boot_ila
run_hw_ila $link_ila

puts "FLOW_PCIE_ONLY_ARMED device=$device"
puts "FLOW_PCIE_ONLY_NEXT power on the host, wait for both ILAs, then run export_capture.tcl"

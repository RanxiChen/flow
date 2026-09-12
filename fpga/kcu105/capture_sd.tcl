# Usage: vivado -mode batch -source capture_sd.tcl -tclargs ABS_LTX ABS_OUTPUT_PREFIX
# No program_hw_devices, reset, or controller register write occurs here.
if {$argc != 2} {error "Expected LTX and fresh output prefix"}
lassign $argv ltx prefix
if {[file exists "${prefix}.vcd"]} {error "Refusing to overwrite capture"}
open_hw_manager
connect_hw_server -url localhost:3121
open_hw_target
set dev [lindex [get_hw_devices xcku040*] 0]
if {$dev eq ""} {error "KU040 not found"}
current_hw_device $dev
set_property PROBES.FILE $ltx $dev
refresh_hw_device $dev
set ila [lindex [get_hw_ilas -of_objects $dev] 0]
set_property CONTROL.TRIGGER_POSITION 1024 $ila
set_property CONTROL.TRIGGER_CONDITION AND $ila
set_property CONTROL.CAPTURE_MODE BASIC $ila
set_property CONTROL.CAPTURE_CONDITION AND $ila
# Refresh with this LTX before configuring; other probe conditions stay don't-care.
set_property TRIGGER_COMPARE_VALUE eq1'b1 [get_hw_probes -of_objects $ila *dbg_sd_send]
set_property TRIGGER_COMPARE_VALUE eq32'h00000805 [get_hw_probes -of_objects $ila *dbg_sd_command]
set_property CAPTURE_COMPARE_VALUE eq1'b1 [get_hw_probes -of_objects $ila *dbg_sd_capture]
run_hw_ila $ila
puts "FLOW_SD_ARMED: now run sdcard_init in BIOS"
wait_on_hw_ila $ila
set data [upload_hw_ila_data $ila]
write_hw_ila_data -force -vcd_file "${prefix}.vcd" $data
write_hw_ila_data -force -csv_file "${prefix}.csv" $data
close_hw_target
disconnect_hw_server
exit

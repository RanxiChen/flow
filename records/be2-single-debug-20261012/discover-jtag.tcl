set_param general.maxThreads 2
load_features labtools
puts [help run_hw_ila]
puts [help upload_hw_ila_data]
puts [help write_hw_ila_data]
open_hw_manager
connect_hw_server -url localhost:3121
set targets [get_hw_targets -quiet]
puts "BE2_JTAG_TARGET_COUNT=[llength $targets]"
puts "BE2_JTAG_TARGETS=$targets"
disconnect_hw_server
close_hw_manager
exit

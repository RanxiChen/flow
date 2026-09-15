# Source in normal hardware-manager mode with the matching .ltx loaded.
# Run BEFORE resuming/loading Linux. USER2 commands require switching the target
# to -jtag_mode on; the armed ILA continues sampling in the FPGA during switching.
proc flight_ila_arm {ila} {
    foreach p [get_hw_probes -of_objects $ila] {
        set width [get_property WIDTH $p]
        set_property TRIGGER_COMPARE_VALUE "eq${width}'b[string repeat x $width]" $p
    }
    set flags [get_hw_probes -of_objects $ila *dbg_flight_flags]
    if {[llength $flags] != 1} {error "Matching flight-recorder LTX probe not found"}
    set_property CONTROL.TRIGGER_POSITION 3072 $ila
    set_property CONTROL.TRIGGER_CONDITION AND $ila
    set_property CONTROL.CAPTURE_MODE ALWAYS $ila
    # flags[4] = configured recorder trigger hit, one sys-clock pulse.
    set_property TRIGGER_COMPARE_VALUE "eq64'b[string repeat x 59]1xxxx" $flags
    run_hw_ila $ila
    puts "Flight ILA armed on recorder trigger; verify recorder is armed separately."
}

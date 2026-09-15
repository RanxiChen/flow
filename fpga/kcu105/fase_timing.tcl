# USER2 is clocked at <=10 MHz. Only single-bit synchronizer entry paths
# are cut. Bundled command/response buses retain explicit delay/skew bounds.
set fase_tck [get_pins -hier -filter {NAME =~ */fase_jtag/tck_buffer/O}]
if {[llength $fase_tck] != 1} { error "FASE TCK buffer missing" }
if {[llength [get_clocks -of_objects $fase_tck]] == 0} {
    create_clock -name fase_tck -period 100.000 $fase_tck
}
proc fase_regs {pattern} {
    set cells [get_cells -hier -filter "NAME =~ */fase_jtag/transport/$pattern"]
    if {[llength $cells] == 0} { error "Missing FASE CDC registers: $pattern" }
    return $cells
}
foreach pair {{cmd_hold_reg* command_reg*} {rsp_hold_reg* scan_reg*}} {
    lassign $pair source dest
    set from [fase_regs $source]
    set to [fase_regs $dest]
    set_max_delay -datapath_only 5.000 -from $from -to $to
    set_bus_skew 5.000 -from $from -to $to
}
foreach entry {req_meta_reg ack_meta_reg seen_meta_reg*} {
    set_false_path -to [get_pins -of_objects [fase_regs $entry] -filter {REF_PIN_NAME == D}]
}
# Reset assertion is asynchronous; each domain has its own two-stage release.
set reset_cells [get_cells -hier -filter {NAME =~ */fase_jtag/transport/*reset_reg*}]
if {[llength $reset_cells] == 0} { error "Missing FASE reset synchronizers" }
set_false_path -to [get_pins -of_objects $reset_cells -filter {REF_PIN_NAME == PRE || REF_PIN_NAME == CLR}]
puts "FLOW_FASE_CDC_CONSTRAINTS_PASS"

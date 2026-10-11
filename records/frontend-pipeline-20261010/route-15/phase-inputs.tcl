# Physical test-environment input sampling, outside frontend and boundary FIFO.
# Inputs are specified relative to the root clock; the DUT BUFG distribution
# adds about 3 ns. Capture the root-launched value at the intervening falling
# edge, then present it to the next internal rising edge. All existing 10 ns,
# 0.1 ns uncertainty and 2.0/0.2 ns I/O delays remain unchanged.
set clock_pin [get_pins globalClock/clock_buffer/O]
if {[llength $clock_pin] != 1} { error "missing actual internal BUFG output" }
set clock_net [get_nets -of_objects $clock_pin]
set vcc [lindex [get_cells -hierarchical -filter {REF_NAME == VCC}] 0]
set gnd [lindex [get_cells -hierarchical -filter {REF_NAME == GND}] 0]
set vcc_net [get_nets -of_objects [get_pins $vcc/P]]
set gnd_net [get_nets -of_objects [get_pins $gnd/G]]
if {[llength $vcc_net] != 1 || [llength $gnd_net] != 1} { error "missing constant rails" }
set output [open phase-inputs.csv w]
puts $output "port,register,sinks"
set_msg_config -id {Netlist 29-68} -new_severity ERROR
set index 0
foreach port [lsort [get_ports -filter {DIRECTION == IN && NAME != clock}]] {
    set segments [get_nets -quiet -segments -of_objects $port]
    if {[llength $segments] == 0} { continue }
    set sinks [get_pins -quiet -leaf -of_objects $segments -filter {DIRECTION == IN}]
    if {[llength $sinks] == 0} { continue }
    # Remove each sink from its own hierarchical net segment, preserving all
    # original logic. No original cells, clocks or timing constraints removed.
    foreach pin $sinks {
        set existing [get_nets -of_objects $pin]
        disconnect_net -net $existing -objects $pin
    }
    set name physical_input_capture_$index
    set cell [create_cell -reference FDRE $name]
    set_property INIT 1'b0 $cell
    set_property IS_C_INVERTED 1'b1 $cell
    connect_net -hier -net $clock_net -objects [get_pins $name/C]
    connect_net -hier -net $vcc_net -objects [get_pins $name/CE]
    connect_net -hier -net $gnd_net -objects [get_pins $name/R]
    connect_net -hier -net [get_nets -of_objects $port] -objects [get_pins $name/D]
    set sampled [create_net ${name}_q]
    connect_net -hier -net $sampled -objects [concat [get_pins $name/Q] $sinks]
    foreach pin [concat [get_pins $name/*] $sinks] {
        if {[llength [get_nets -quiet -of_objects $pin]] == 0} { error "unconnected input sampling pin $pin" }
    }
    puts $output "$port,$name,[llength $sinks]"
    incr index
}
close $output
puts "PHYSICAL_INPUT_CAPTURE added=$index; frontend cells unchanged"
if {$index == 0} { error "no input environment registers inserted" }

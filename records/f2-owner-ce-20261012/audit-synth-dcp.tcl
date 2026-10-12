# Read-only mapping check on the exact completed production synthesis checkpoint.
open_checkpoint /home/chen/FUN/flow-runs/f2-owner-ce-d4350cb-retry2-20261012/single/gateware/xilinx_kcu105_synth.dcp
set payload_regs [get_cells -quiet -hier -filter {NAME =~ *frontend/cache/slots* && IS_SEQUENTIAL}]
set ce_pins [get_pins -quiet -of_objects $payload_regs -filter {REF_PIN_NAME == CE}]
set stream [open f2-payload-ce-audit-corrected.txt w]
puts $stream "payload_registers [llength $payload_regs]"
puts $stream "payload_ce_pins [llength $ce_pins]"
if {[llength $payload_regs] == 0 || [llength $ce_pins] != [llength $payload_regs]} {
    close $stream
    error "Mapped payload slots or CE pins missing"
}
set starts [all_fanin -flat -startpoints_only -to $ce_pins]
set unexpected {}
foreach pin $starts {
    puts $stream "CE_STARTPOINT $pin"
    # Vivado reports a sequential timing startpoint at its clock /C, rather
    # than its data output /Q, for this all_fanin query.
    if {![regexp {/(qValid|refillFault|tail)_reg[^/]*/(C|Q)$} $pin]} {
        lappend unexpected $pin
    }
}
puts $stream "unexpected_startpoints [llength $unexpected]"
close $stream
report_timing -delay_type max -to $ce_pins -max_paths 20 -nworst 1 -file f2-payload-ce-timing-corrected.rpt
if {[llength $unexpected] != 0} {error "Payload CE has non-owner startpoints: $unexpected"}
puts "FLOW_F2_OWNER_CE_GATE PASS: [llength $payload_regs] registers, [llength $starts] owner startpoints"
close_design
exit

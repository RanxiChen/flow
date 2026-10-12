#!/usr/bin/env python3
"""Keep the production synthesis prefix and gates, then audit payload CE cones."""
import hashlib
import json
from pathlib import Path
import sys

gateware = Path(sys.argv[1]).resolve()
original = gateware / "xilinx_kcu105.tcl"
text = original.read_text()
marker = "# Optimize design\n"
if text.count(marker) != 1:
    raise SystemExit("Expected exactly one production optimize boundary")
prefix = text.split(marker, 1)[0]
if "synth_design -directive default" not in prefix or "write_checkpoint -force xilinx_kcu105_synth.dcp" not in prefix:
    raise SystemExit("Production default synthesis/checkpoint missing")
audit = r'''
# Source-side cancellation ends at ownership; inspect the actual mapped CE cone.
report_timing -delay_type max -max_paths 30 -nworst 1 -file f2-synth-max-paths.rpt
report_control_sets -verbose -file f2-synth-control-sets.rpt
set payload_regs [get_cells -quiet -hier -filter {NAME =~ *frontend/cache/slots* && IS_SEQUENTIAL}]
set ce_pins [get_pins -quiet -of_objects $payload_regs -filter {REF_PIN_NAME == CE}]
set stream [open f2-payload-ce-audit.txt w]
puts $stream "payload_registers [llength $payload_regs]"
puts $stream "payload_ce_pins [llength $ce_pins]"
foreach cell $payload_regs {puts $stream "REGISTER $cell"}
if {[llength $payload_regs] == 0 || [llength $ce_pins] == 0} {
    close $stream
    error "No mapped frontend slots/CE found; structural claim cannot be checked"
}
set starts [all_fanin -flat -startpoints_only -to $ce_pins]
set unexpected {}
foreach pin $starts {
    puts $stream "CE_STARTPOINT $pin"
    if {![regexp {/(qValid|refillFault|tail)_reg[^/]*/Q$} $pin]} {
        lappend unexpected $pin
    }
}
puts $stream "unexpected_startpoints [llength $unexpected]"
close $stream
report_timing -delay_type max -to $ce_pins -max_paths 20 -nworst 1 -file f2-payload-ce-timing.rpt
if {[llength $unexpected] != 0} {
    error "Payload CE has non-owner startpoints: $unexpected"
}
puts "FLOW_F2_OWNER_CE_GATE PASS: [llength $payload_regs] registers, [llength $starts] owner startpoints"
exit
'''
output = gateware / "f2-synth-only.tcl"
output.write_text(prefix + audit)
binding = {
    "production_tcl": str(original),
    "production_tcl_sha256": hashlib.sha256(original.read_bytes()).hexdigest(),
    "synth_only_tcl": str(output),
    "synth_only_tcl_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
    "identical_production_prefix": True,
    "stop_before": "opt_design/place_design/route_design/write_bitstream",
    "new_timing_exceptions": False,
}
(gateware / "f2-synth-script-binding.json").write_text(json.dumps(binding, indent=2) + "\n")

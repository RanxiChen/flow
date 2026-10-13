#!/usr/bin/env python3
"""Stop the generated KCU105 Vivado flow after synthesis and reports."""

import hashlib
import json
import sys
from pathlib import Path


gateware = Path(sys.argv[1]).resolve()
production = gateware / "xilinx_kcu105.tcl"
source = production.read_text()
marker = "# Optimize design\n"
if source.count(marker) != 1:
    raise SystemExit("expected exactly one production optimization boundary")
prefix = source.split(marker, 1)[0]
if "synth_design -directive default" not in prefix:
    raise SystemExit("production default synthesis is missing")
if "read_xdc xilinx_kcu105.xdc" not in prefix:
    raise SystemExit("production XDC is missing")
output = gateware / "r07c-synth-only.tcl"
output.write_text(prefix + """
report_timing -delay_type max -max_paths 10 -nworst 1 -path_type full -file r07c-worst10-distinct-synth.rpt
write_checkpoint -force r07c-synth.dcp
exit
""")
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
(gateware / "r07c-synth-script-binding.json").write_text(json.dumps({
    "production_tcl": str(production),
    "production_sha256": digest(production),
    "synth_tcl": str(output),
    "synth_sha256": digest(output),
    "same_production_prefix": True,
    "stop_before": "opt_design/place_design/route_design/write_bitstream",
}, indent=2) + "\n")

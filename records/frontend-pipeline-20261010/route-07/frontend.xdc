create_clock -name frontend_clk -period 10.000 [get_ports clock]
set_clock_uncertainty 0.100 [get_clocks frontend_clk]
# Registered standalone boundary: external launch/capture allowance is 2 ns.
# Explicit minimum delays also model hold; no zero-delay OOC interpretation.
set frontend_inputs [get_ports -filter {DIRECTION == IN && NAME != clock}]
set_input_delay -clock frontend_clk -max 2.000 $frontend_inputs
set_input_delay -clock frontend_clk -min 0.200 $frontend_inputs
set_output_delay -clock frontend_clk -max 2.000 [all_outputs]
set_output_delay -clock frontend_clk -min 0.200 [all_outputs]

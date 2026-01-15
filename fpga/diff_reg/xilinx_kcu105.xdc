################################################################################
# IO constraints
################################################################################
# cpu_reset:0
set_property LOC AN8 [get_ports {cpu_reset}]
set_property IOSTANDARD LVCMOS18 [get_ports {cpu_reset}]

# clk125:0.p
set_property LOC G10 [get_ports {clk125_p}]
set_property IOSTANDARD LVDS [get_ports {clk125_p}]

# clk125:0.n
set_property LOC F10 [get_ports {clk125_n}]
set_property IOSTANDARD LVDS [get_ports {clk125_n}]
set_property LOC AP8 [get_ports {user_led}]
set_property IOSTANDARD LVCMOS18 [get_ports {user_led}]
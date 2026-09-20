# Only asynchronous reset assertion pins on the named synchronizers are cut.
# Release stages and the complete ref-clock -> MMCM path remain timed.
set flow_reset_cells [get_cells -hier -filter {NAME =~ *pcie_reset_supervisor/*_reg* && ASYNC_REG == TRUE}]
if {[llength $flow_reset_cells] < 15} { error "PCIe reset synchronizers missing" }
set flow_reset_async [get_pins -of_objects $flow_reset_cells -filter {REF_PIN_NAME == PRE || REF_PIN_NAME == CLR}]
if {[llength $flow_reset_async] < 15} { error "PCIe asynchronous assertion pins missing" }
set_false_path -to $flow_reset_async
puts "FLOW_PCIE_RESET_CONSTRAINTS_PASS"

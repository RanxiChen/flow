# The single-RVV preset explicitly substitutes dataless Home for L2.
# Do not apply the scalar L2 RAM gate to an absent cache.
if {[llength [get_cells -quiet -hierarchical -filter {REF_NAME =~ L2Home*}]]} {
    error "RVV preset unexpectedly retained L2"
}
set homes [get_cells -quiet -hierarchical -filter {REF_NAME =~ RvvDatalessHome*}]
set vectors [get_cells -quiet -hierarchical -filter {REF_NAME =~ RvvCoprocessor*}]
if {![llength $homes] || ![llength $vectors]} { error "RVV Home/backend missing after synthesis" }
set ram [get_cells -quiet -hierarchical -filter {REF_NAME =~ RAMB36* || REF_NAME =~ RAMB18*}]
if {![llength $ram]} { error "RVV product has no block RAM" }
report_utilization -hierarchical -file rvv-mapping-utilization.rpt
puts "RVV_DATALESS_HOME_MAPPING_GATE_PASS"

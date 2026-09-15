# Post-route acceptance; usage: vivado -mode batch -source flight_report.tcl
# -tclargs ABS_ROUTED_DCP ABS_FRESH_REPORT_DIRECTORY
if {$argc != 2} {error "Expected routed DCP and fresh report directory"}
lassign $argv checkpoint reports
if {[file exists $reports]} {error "Report directory already exists"}
file mkdir $reports
open_checkpoint $checkpoint
report_timing_summary -delay_type min_max -report_unconstrained -file $reports/timing.rpt
report_utilization -hierarchical -file $reports/utilization.rpt
report_cdc -details -file $reports/cdc.rpt
set rams [get_cells -hier -filter {NAME =~ *recorder* && REF_NAME =~ RAMB*}]
if {[llength $rams] == 0} {error "Recorder block RAM not found"}
set ramclocks [get_clocks -of_objects [get_pins -of_objects $rams -filter {REF_PIN_NAME == CLKARDCLK}]]
if {[llength $ramclocks] == 0} {error "Recorder RAM clock missing"}
foreach c $ramclocks {
    if {abs([get_property PERIOD $c] - 10.0) > 0.001} {error "Recorder is not clocked at 100 MHz: $c"}
}
set setup [get_timing_paths -delay_type max -max_paths 1]
set hold [get_timing_paths -delay_type min -max_paths 1]
if {[llength $setup] != 1 || [llength $hold] != 1} {error "Timing paths unavailable"}
set wns [get_property SLACK $setup]
set whs [get_property SLACK $hold]
set f [open $reports/result.txt w]
puts $f "recorder_ram_primitives=[llength $rams] WNS=$wns WHS=$whs"
close $f
if {$wns < 0 || $whs < 0} {error "100 MHz setup/hold timing failed; see reports"}
puts "FLIGHT_TIMING_PASS recorder_ram_primitives=[llength $rams] WNS=$wns WHS=$whs"
# CDC report still requires review; this gate does not certify board operation.
close_design

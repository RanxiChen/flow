set_param general.maxThreads 2
open_checkpoint [lindex $argv 0]
report_timing_summary -delay_type min_max -report_unconstrained -file timing-summary.rpt
report_timing -delay_type max -max_paths 20 -nworst 1 -file worst-20.rpt
report_drc -checks {LUTLP-1} -file timing-loops-drc.rpt
report_exceptions -file timing-exceptions.rpt
set out [open inferred-loop-objects.txt w]
foreach pattern {*alui_23* *sel0*} {
    puts $out "PATTERN $pattern"
    foreach cell [get_cells -hier -quiet $pattern] {
        puts $out "CELL $cell [get_property REF_NAME $cell]"
    }
    foreach pin [get_pins -hier -quiet $pattern] {puts $out "PIN $pin"}
}
close $out
exit

# Read-only post-synthesis queries. No keep, retiming or timing exceptions.
set registers [all_registers -cells]
set s2starts [filter $registers {NAME =~ l1d/internal2* || NAME =~ l1d/cpu2* || NAME =~ */l1d/internal2* || NAME =~ */l1d/cpu2*}]
set summary [open soc3b-paths.tsv w]
puts $summary "class\tobjects\tpaths\tworst_slack\tlogic_levels\tstatus"
proc through_query {label expression} {
    global s2starts summary
    set points [get_pins -hierarchical -quiet -filter $expression]
    if {![llength $points]} {
        puts $summary "$label\t0\t0\tNA\tNA\toptimized_or_unmapped_use_RTL_fanin"
        return
    }
    set paths [get_timing_paths -quiet -from $s2starts -through $points -max_paths 20 -nworst 1]
    if {![llength $paths]} {
        puts $summary "$label\t[llength $points]\t0\tNA\tNA\tno_timed_path_for_matched_objects"
    } else {
        set p [lindex $paths 0]
        puts $summary "$label\t[llength $points]\t[llength $paths]\t[get_property SLACK $p]\t[get_property LOGIC_LEVELS $p]\tpath_present_review_required"
        report_timing -from $s2starts -through $points -max_paths 20 -nworst 1 -path_type full -file "$label.rpt"
    }
}
through_query forbidden-writeport {NAME =~ */writeback/io_gprWrite_valid || NAME =~ */writeback/io_fprWrite_valid || NAME =~ */regFile/io_rd_en || NAME =~ */fpRegFile/io_write_valid}
through_query forbidden-result-ready {NAME =~ */writeback/io_div_ready || NAME =~ */writeback/io_mul_ready || NAME =~ */writeback/io_fp_ready || NAME =~ */writeback/io_late_ready}
through_query forbidden-actual-clear {NAME =~ */scoreboard/io_clear_valid || NAME =~ */writeback/io_clear_valid}
through_query forbidden-RAW-WAW {NAME =~ */scoreboard/io_hazard || NAME =~ */scoreboard/io_sourceStall*}
proc endpoint_query {label expression} {
    global s2starts summary registers
    set cells [filter $registers $expression]
    if {![llength $cells]} {
        puts $summary "$label\t0\t0\tNA\tNA\tunmapped"
        return
    }
    set paths [get_timing_paths -quiet -from $s2starts -to $cells -max_paths 20 -nworst 1]
    if {![llength $paths]} {
        puts $summary "$label\t[llength $cells]\t0\tNA\tNA\tno_timed_path_for_matched_objects"
    } else {
        set p [lindex $paths 0]
        puts $summary "$label\t[llength $cells]\t[llength $paths]\t[get_property SLACK $p]\t[get_property LOGIC_LEVELS $p]\tallowed_endpoint_review"
        report_timing -from $s2starts -to $cells -max_paths 20 -nworst 1 -path_type full -file "$label.rpt"
    }
}
endpoint_query allowed-W2-input {NAME =~ */writeback/w2*}
endpoint_query allowed-HPM-input {NAME =~ */csrFile/hpm/pending* || NAME =~ */csrFile/*pending*}
endpoint_query allowed-hold-cancel {NAME =~ */ex_valid_reg || NAME =~ */mem_valid_reg || NAME =~ */wb_valid_reg}
endpoint_query allowed-Mshr-busy {NAME =~ */scoreboard/gprBusy* || NAME =~ */scoreboard/fprBusy*}
# EX data endpoints include the permitted hold/advance/cancel selection. They
# are reported for review, not classified as a forbidden bypass path by name.
endpoint_query EX-data-with-hold-review {NAME =~ */ex_rs1_data* || NAME =~ */ex_rs2_data* || NAME =~ */exFpr*}
close $summary
set out [open worst-20-paths.tsv w]
puts $out "startpoint\tendpoint\tslack\tlogic_levels"
foreach p [get_timing_paths -max_paths 20 -nworst 1] {
    puts $out "[get_property STARTPOINT_PIN $p]\t[get_property ENDPOINT_PIN $p]\t[get_property SLACK $p]\t[get_property LOGIC_LEVELS $p]"
}
close $out
set out [open soc3b-mapped-objects.txt w]
puts $out "S2 registers [llength $s2starts]"
foreach c $s2starts { puts $out [get_property NAME $c] }
foreach p [get_pins -hierarchical -quiet -filter {NAME =~ */writeback/io_* || NAME =~ */scoreboard/io_*}] { puts $out [get_property NAME $p] }
close $out

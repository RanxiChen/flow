# Complete root summary, including OSERDESE3 Max Skew reported under pulse width.
# Reject absent/incomplete evidence; never relax timing constraints.
proc flow_require_routed_timing {report_path} {
    set stream [open $report_path r]
    set report [read $stream]
    close $stream
    set section [string first {| Design Timing Summary} $report]
    if {$section < 0} {error "Missing design timing summary; bitstream withheld"}
    foreach line [split [string range $report $section end] "\n"] {
        set fields [regexp -all -inline {\S+} $line]
        if {[llength $fields] != 12} {continue}
        set numeric 1
        foreach value $fields {
            if {![regexp {^-?[0-9]+(?:\.[0-9]+)?$} $value]} {set numeric 0; break}
        }
        if {!$numeric} {continue}
        foreach index {0 4 8} {
            if {[lindex $fields $index] < 0} {error "Routed setup/hold/pulse-or-skew failed: $fields; bitstream withheld"}
        }
        foreach index {1 2 5 6 9 10} {
            if {[lindex $fields $index] != 0} {error "Routed failed endpoints/total slack: $fields; bitstream withheld"}
        }
        puts "FLOW_COMPLETE_TIMING_GATE PASS: $fields"
        return
    }
    error "No complete design timing row; bitstream withheld"
}

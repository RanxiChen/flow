# Same queries run against exact baseline and BE2 checkpoints. No keep/XDC edits.
set checkpoint [lindex $argv 0]
set output [lindex $argv 1]
file mkdir $output
set_param general.maxThreads 2
open_checkpoint $checkpoint
set metadata [open [file join $output collections.txt] w]
puts $metadata "checkpoint=$checkpoint"
puts $metadata "vivado=[version -short]"
proc collect_nets {pattern label} {
    global metadata output
    set nets [get_nets -quiet -hierarchical -regexp $pattern]
    set pins [get_pins -quiet -leaf -of_objects $nets -filter {DIRECTION == OUT}]
    puts $metadata "$label nets=[llength $nets] drivers=[llength $pins]"
    set f [open [file join $output $label-sources.txt] w]
    puts $f "NETS\n[join $nets \n]\nDRIVERS\n[join $pins \n]"
    close $f
    return $pins
}
proc endpoints {label filter pinfilter} {
    global metadata output
    set cells [get_cells -quiet -hierarchical -filter $filter]
    set pins [get_pins -quiet -of_objects $cells -filter $pinfilter]
    puts $metadata "$label cells=[llength $cells] endpoints=[llength $pins]"
    set f [open [file join $output $label-endpoints.txt] w]
    puts $f "[join $pins \n]"
    close $f
    return $pins
}
proc collect_ports {pattern label} {
    global metadata output
    set ports [get_pins -quiet -hierarchical -regexp $pattern]
    set nets [get_nets -quiet -of_objects $ports]
    set pins [get_pins -quiet -leaf -of_objects $nets -filter {DIRECTION == OUT}]
    puts $metadata "$label ports=[llength $ports] nets=[llength $nets] drivers=[llength $pins]"
    set f [open [file join $output $label-sources.txt] w]
    puts $f "PORTS\n[join $ports \n]\nNETS\n[join $nets \n]\nDRIVERS\n[join $pins \n]"
    close $f
    return $pins
}
proc query {label starts ends} {
    global metadata output
    if {![llength $starts] || ![llength $ends]} {
        puts $metadata "$label UNRESOLVED_EMPTY_COLLECTION"
        return
    }
    set cone [all_fanout -quiet -flat -from $starts -endpoints_only]
    set reachable [dict create]
    foreach e $cone { dict set reachable $e 1 }
    set hits [list]
    foreach e $ends { if {[dict exists $reachable $e]} {lappend hits $e} }
    set f [open [file join $output $label-fanout.txt] w]
    puts $f "all_fanout -flat -from <sources> -endpoints_only\nHITS=[llength $hits]\n[join $hits \n]\nALL_ENDPOINTS\n[join $cone \n]"
    close $f
    set paths [get_timing_paths -quiet -through $starts -to $ends -max_paths 20 -nworst 20]
    puts $metadata "$label fanout_hits=[llength $hits] paths=[llength $paths]"
    flush $metadata
    puts "BE2_QUERY $label hits=[llength $hits] paths=[llength $paths]"
    report_timing -through $starts -to $ends -max_paths 20 -nworst 20 -path_type full -file [file join $output $label-worst20.rpt]
}
set decision [collect_nets {.*(l1d|backend).*(io_core_resp_valid|io_core_resp_bits_kind|io_l1d_resp_valid|io_l1d_resp_bits_kind).*} s3decision]
set hold [collect_nets {.*l1d.*(cpuHold|io_core_s2Hold).*} cpuhold]
set grant [collect_ports {.*backend.*/(writeback/io_(mul|div|fp)_ready|mulUnit/io_result_ready|divUnit/io_result_ready|fpUnit/io_result_ready)} grant_ports]
if {![llength $grant]} {
    set grant [collect_nets {.*(writeback.*(io_grant|selected|io_(mul|div|fp)_ready)|backend.*_writeback_io_(mul|div|fp)_ready).*} grant]
}
set payload [endpoints payload_enable {IS_SEQUENTIAL == 1 && (NAME =~ *backend*/ex_* || NAME =~ *backend*/mem_* || NAME =~ *backend*/perm_*) && NAME !~ *valid* && NAME !~ *Sent* && NAME !~ *Issued*} {DIRECTION == IN && REF_PIN_NAME =~ CE*}]
set btb [endpoints btb_payload_control {IS_SEQUENTIAL == 1 && NAME =~ *backend*/btb_* && NAME !~ *valid* && NAME !~ *PayloadVisible*} {DIRECTION == IN && (REF_PIN_NAME =~ R* || REF_PIN_NAME =~ S* || REF_PIN_NAME =~ CE*)}]
set execution [endpoints execution_control {IS_SEQUENTIAL == 1 && (NAME =~ *backend*/mulUnit/* || NAME =~ *backend*/divUnit/* || NAME =~ *backend*/fpUnit/*)} {DIRECTION == IN && REF_PIN_NAME =~ CE*}]
set frontend [endpoints frontend_control {IS_SEQUENTIAL == 1 && NAME =~ *cluster/frontend*/* && NAME !~ *frontendBoundary*} {DIRECTION == IN && (REF_PIN_NAME == D || REF_PIN_NAME =~ CE*)}]
set arithmetic [endpoints arithmetic_enable {(NAME =~ *backend*/mulUnit/* && (REF_NAME == DSP48E2 || NAME =~ *pipeline* || NAME =~ *s0_* || NAME =~ *s1_* || NAME =~ *s2_*)) || NAME =~ *backend*/fpUnit/impl/*} {DIRECTION == IN && REF_PIN_NAME =~ CE*}]
set l1d [endpoints l1d_internal {IS_SEQUENTIAL == 1 && NAME =~ *cluster/l1d*/*} {DIRECTION == IN && (REF_PIN_NAME == D || REF_PIN_NAME =~ CE*)}]
set exceptions [endpoints local_exceptions {IS_SEQUENTIAL == 1 && ((NAME =~ *backend*/ex_valid* || NAME =~ *backend*/mem_valid* || NAME =~ *backend*/perm_valid* || NAME =~ *backend*/wb_valid*) || NAME =~ *backend*/writeback/w2_* || NAME =~ *backend*/scoreboard/* || NAME =~ *backend*/csrFile/* || NAME =~ *backend*/btb_valid* || NAME =~ *backend*/btbPayloadVisible* || NAME =~ *frontendBoundary/slowRedirect* || NAME =~ *l1d*/cpu*valid*)} {DIRECTION == IN && (REF_PIN_NAME == D || REF_PIN_NAME =~ CE*)}]
foreach {label starts} [list decision $decision hold $hold] {
    query $label-payload $starts $payload
    query $label-btb $starts $btb
    query $label-execution $starts $execution
    query $label-frontend $starts $frontend
    query $label-allowed $starts $exceptions
}
query grant-arithmetic $grant $arithmetic
query grant-l1d $grant $l1d
# The baseline optimizer merges grant into capacity/enable logic. Query the
# identical registered ordinary-write arbitration inputs as a conservative
# positive control; do not rename these pins to raw grant.
set grant_context [endpoints grant_context {IS_SEQUENTIAL == 1 && (NAME =~ *backend*/writeback/w2_valid_reg* || NAME =~ *backend*/writeback/w2_bits_rd_isFp_reg*)} {DIRECTION == OUT && REF_PIN_NAME == Q}]
query grant-context-arithmetic $grant_context $arithmetic
query grant-context-l1d $grant_context $l1d
set wbdecision [collect_nets {.*backend.*(wbCommit|wbKill).*} wbdecision]
query wbdecision-local $wbdecision $exceptions
report_utilization -hierarchical -file [file join $output utilization-hierarchical.rpt]
report_timing_summary -file [file join $output timing-summary.rpt]
close $metadata
close_design

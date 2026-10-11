set_param general.maxThreads 2
open_checkpoint [lindex $argv 0]
set f [open [lindex $argv 1] w]
foreach pattern [list {.*backend.*/writeback/io_.*ready.*} {.*backend.*/(mulUnit|divUnit|fpUnit)/io_result_ready.*} {.*backend.*(wbCommit|wbKill).*}] {
    set pins [get_pins -quiet -hierarchical -regexp $pattern]
    set nets [get_nets -quiet -of_objects $pins]
    puts $f "$pattern\nPORTS=[llength $pins]\n[join $pins \n]\nNETS=[llength $nets]\n[join $nets \n]"
}
close $f
close_design

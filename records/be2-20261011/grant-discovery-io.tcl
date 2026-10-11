set_param general.maxThreads 2
open_checkpoint [lindex $argv 0]
set out [open [lindex $argv 1] w]
set nets [get_nets -quiet -hierarchical -regexp {.*backend.*(ready|grant|selected).*}]
puts $out "[join $nets \n]"
close $out
exit

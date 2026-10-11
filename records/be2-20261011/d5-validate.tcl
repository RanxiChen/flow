set_param general.maxThreads 2
open_checkpoint [lindex $argv 0]
cd [lindex $argv 1]
source [lindex $argv 2]
set home [get_property NAME [lindex $homes 0]]
set blocks [get_cells -quiet -hierarchical -filter {NAME =~ *cluster/data/ram/* && REF_NAME =~ RAMB*}]
if {![llength $blocks]} {error "D5 negative check requires nonempty hoisted data RAM"}
if {![catch {d5_verify_owner "$home/incorrect_owner" $blocks} message]} {error "D5 accepted unrelated RAM"}
puts "D5_NEGATIVE_OWNER_REJECTED: $message"
exit

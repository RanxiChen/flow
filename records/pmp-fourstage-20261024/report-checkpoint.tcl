set_param general.maxThreads 4
set checkpoint [file normalize [lindex $argv 0]]
set common_dir [file normalize [lindex $argv 1]]
set part xcku040-ffva1156-2-e
open_checkpoint $checkpoint
source $common_dir/summarize.tcl

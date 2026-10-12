set_param general.maxThreads 2
set out [lindex $argv 1]
file mkdir $out
open_checkpoint [lindex $argv 0]
set fd [open "$out/cells.txt" w]
foreach {label pattern} {
  fetch_return */frontend*/cache/returns/ram_ext/*
  late_return */backend*/writeback/lateQueue/ram_ext/*
  csr */backend*/csrFile/*
  fetch_cache */frontend*/cache/*
} {
  set cells [get_cells -quiet -hierarchical -filter "IS_PRIMITIVE == 1 && NAME =~ $pattern"]
  puts $fd "GROUP $label cells=[llength $cells]"
  array unset counts
  foreach c $cells {
    set ref [get_property REF_NAME $c]
    if {![info exists counts($ref)]} {set counts($ref) 0}
    incr counts($ref)
  }
  foreach ref [lsort [array names counts]] {puts $fd "REF $ref $counts($ref)"}
  set n 0
  foreach c $cells {
    set ref [get_property REF_NAME $c]
    if {[string match LUT* $ref] || [string match RAM* $ref]} {
      puts $fd "CELL $ref $c"
      incr n
      if {$n >= 50} {break}
    }
  }
  if {[llength $cells] > 0} {report_property -all [lindex $cells 0] -file "$out/$label-property.txt"}
}
close $fd
puts "FLOW_READONLY_AUDIT_DONE $out"
close_design
exit 0

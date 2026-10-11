# Give OOC data ports real interconnect anchors near their placed boundary
# registers/logic. This supplies physical context, not timing exceptions.
# All 10 ns / 2 ns max / 0.2 ns min timing constraints remain unchanged.
set int_tiles [get_tiles -filter {TYPE == INT}]
if {[llength $int_tiles] == 0} { error "device has no INT tiles" }
set tile_at [dict create]
foreach tile $int_tiles x [get_property GRID_POINT_X $int_tiles] y [get_property GRID_POINT_Y $int_tiles] {
  dict set tile_at "$x,$y" $tile
}
set used [dict create]
set summary [open partpins.csv w]
set constraints [open partpins.xdc w]
puts $summary "port,direction,cell,site,anchor,radius"
set anchored 0
set disconnected 0
set constant_outputs 0
set fallback_cell [lindex [get_cells -hierarchical -filter {REF_NAME == FDRE}] 0]
foreach port [lsort [get_ports -filter {NAME != clock}]] {
  set direction [get_property DIRECTION $port]
  if {$direction == "IN"} {
    set ends [all_fanout -flat -endpoints_only -from $port]
  } else {
    set ends [all_fanin -flat -startpoints_only -to $port]
  }
  set cell ""
  foreach candidate [get_cells -quiet -of_objects $ends] {
    set loc [get_property LOC $candidate]
    if {$loc != ""} { set cell $candidate; break }
  }
  if {$cell == "" && $direction == "OUT"} {
    # Constant outputs have no timed startpoint but still need a physical
    # endpoint for partial routing/export. Use a real placed register's area.
    set cell $fallback_cell
    incr constant_outputs
  }
  if {$cell == ""} { incr disconnected; continue }
  set site [get_sites [get_property LOC $cell]]
  set tile [get_tiles -of_objects $site]
  set x [get_property GRID_POINT_X $tile]
  set y [get_property GRID_POINT_Y $tile]
  set selected ""
  for {set radius 0} {$radius <= 32 && $selected == ""} {incr radius} {
    for {set dx [expr {-$radius}]} {$dx <= $radius && $selected == ""} {incr dx} {
      set dy [expr {$radius - abs($dx)}]
      foreach sy [lsort -unique [list $dy [expr {-$dy}]]] {
        set key "[expr {$x+$dx}],[expr {$y+$sy}]"
        if {![dict exists $tile_at $key]} { continue }
        set possible [dict get $tile_at $key]
        if {[dict exists $used $possible] && [dict get $used $possible] >= 8} { continue }
        set selected $possible
        break
      }
    }
  }
  if {$selected == ""} { error "no physical OOC anchor for $port near $cell" }
  dict incr used $selected
  set_property HD.PARTPIN_LOCS $selected $port
  puts $summary "$port,$direction,$cell,$site,$selected,[expr {$radius-1}]"
  puts $constraints "set_property HD.PARTPIN_LOCS $selected \[get_ports \{$port\}\]"
  incr anchored
}
close $summary
close $constraints
puts "OOC_CONTEXT anchored=$anchored constant_outputs=$constant_outputs disconnected_inputs=$disconnected INT_tiles=[dict size $used]"

# This is a local synchronous fixture, not a board-to-BUFG interface.
# Reference each fixture port to the clock pin of its boundary register.
# Keep the same max/min data budgets and all INTERNAL 5 ns paths untouched.
set f [open fixture-references.csv w]
puts $f "port,direction,reference_clock_pin"
set referenced 0
foreach port [get_ports -filter {NAME != clock}] {
  set direction [get_property DIRECTION $port]
  if {$direction == "IN"} {
    set endpoints [all_fanout -flat -endpoints_only -from $port]
  } else {
    set endpoints [all_fanin -flat -startpoints_only -to $port]
  }
  set ref ""
  foreach cell [get_cells -quiet -of_objects $endpoints -filter {IS_SEQUENTIAL == 1}] {
    set pins [get_pins -quiet -of_objects $cell -filter {IS_CLOCK == 1 && DIRECTION == IN}]
    if {[llength $pins]} {set ref [lindex $pins 0]; break}
  }
  if {$ref == ""} {
    # Alignment/assertion-only input bits can disappear in synthesis; they
    # have no timed endpoint and retain the common default I/O constraint.
    if {$direction == "IN" && [llength $endpoints] != 0} {
      error "no boundary clock reference for active input $port"
    }
    puts $f "$port,$direction,NO_TIMED_ENDPOINT"
    continue
  }
  if {$direction == "IN"} {
    set_input_delay -clock experiment_clk -reference_pin $ref -max 0.500 $port
    set_input_delay -clock experiment_clk -reference_pin $ref -min 0.500 $port
  } else {
    set_output_delay -clock experiment_clk -reference_pin $ref -max 0.500 $port
    set_output_delay -clock experiment_clk -reference_pin $ref -min 0.500 $port
  }
  puts $f "$port,$direction,$ref"
  incr referenced
}
close $f
puts "FIXTURE_REFERENCES $referenced ports; max/min unchanged at 0.5/0.5 ns"

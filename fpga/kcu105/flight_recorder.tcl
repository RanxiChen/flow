# Source after fase_jtag.tcl and fase_select in USER2 JTAG mode.
# No command here launches, halts, resets or reprograms the CPU/FPGA.
proc flight_cmd {op {index 0} {data 0}} {
    set r [fase_command $op $index $data]
    if {[dict get $r error]} {error "Flight recorder rejected opcode $op index $index"}
    return [dict get $r data]
}
proc flight_arm {{address 0} {mask 0xffffffffffffffff} {page_fault 0} {address_match 1} {privileges 15} {post_cycles 32}} {
    if {$post_cycles < 0 || $post_cycles > 65535 || $privileges < 0 || $privileges > 15 ||
        $page_fault ni {0 1} || $address_match ni {0 1}} {error "Invalid flight trigger settings"}
    flight_cmd 18 0 $address
    flight_cmd 20 0 $mask
    flight_cmd 17 0 [expr {1 | ($page_fault << 3) | ($address_match << 4) |
        ($privileges << 8) | ($post_cycles << 16)}]
    return [flight_cmd 16]
}
proc flight_freeze {} {flight_cmd 17 0 2}
proc flight_dump {filename} {
    set status [flight_cmd 16]
    if {$status & 1} {error "Recorder is active; wait for trigger freeze or call flight_freeze"}
    set depth [expr {($status >> 16) & 65535}]
    set banks [expr {($status >> 8) & 255}]
    if {$depth != 1024 || $banks != 6} {error "Unknown flight recorder geometry"}
    set out [open $filename {WRONLY CREAT EXCL}]
    try {
        puts $out "# format=flow-flight-v1 status=[fase_hex $status 16] trigger_cycle=[flight_cmd 16 7]"
        puts $out "bank,slot,cycle,flags,word2,word3,word4,word5,word6,word7"
        for {set bank 0} {$bank < $banks} {incr bank} {
            set st [flight_cmd 16 [expr {$bank+1}]]
            set count [expr {($st >> 16) & 65535}]
            set next [expr {$st & 65535}]
            set first [expr {$count == $depth ? $next : 0}]
            puts $out "# bank=$bank count=$count next=$next total=[expr {$st >> 32}] trigger_slot=[flight_cmd 16 [expr {$bank+8}]]"
            for {set n 0} {$n < $count} {incr n} {
                set slot [expr {($first + $n) % $depth}]
                set row [list $bank $slot]
                for {set word 0} {$word < 8} {incr word} {
                    lappend row [fase_hex [flight_cmd 19 0 [expr {($bank << 13) | ($slot << 3) | $word}]] 16]
                }
                puts $out [join $row ,]
            }
        }
    } finally {close $out}
    return $filename
}

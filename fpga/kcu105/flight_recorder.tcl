# Recorder v2. Source after fase_jtag.tcl and fase_select in USER2 JTAG mode.
# Recording starts at reset. These commands do not stop or reset the CPU.
proc flight_cmd {op {index 0} {data 0}} {
    set r [fase_command $op $index $data]
    if {[dict get $r error]} {error "Flight recorder rejected opcode $op index $index data $data"}
    return [dict get $r data]
}
proc flight_require_v2 {} {
    set status [flight_cmd 16]
    if {($status >> 32) != 2} {error "Requires recorder v2 bitstream; do not use v1 ARM/FREEZE commands"}
    return $status
}
proc flight_faults {} {
    flight_require_v2
    set result {}
    set slots [flight_cmd 16 11]
    for {set slot 0} {$slot < $slots} {incr slot} {
        # Unleased slots may change while listing. Reject a torn descriptor.
        for {set retry 0} {$retry < 3} {incr retry} {
            set id [flight_cmd 17 1 $slot]
            set flags [flight_cmd 17 0 $slot]
            if {!($flags & 1)} {break}
            set cycle [flight_cmd 17 2 $slot]
            set state [flight_cmd 17 3 $slot]
            set pc [flight_cmd 17 4 $slot]
            set cause [flight_cmd 17 6 $slot]
            if {$id == [flight_cmd 17 1 $slot]} {
                lappend result [list $id [dict create id $id slot $slot ready [expr {($flags >> 1) & 1}] \
                    leased [expr {($flags >> 2) & 1}] manual [expr {($flags >> 3) & 1}] \
                    cycle $cycle pc $pc cause $cause privilege [expr {($state >> 16) & 3}]]]
                break
            }
        }
    }
    set ordered {}
    foreach pair [lsort -integer -index 0 $result] {lappend ordered [lindex $pair 1]}
    return $ordered
}
proc flight_list {} {
    set faults [flight_faults]
    puts "captures=[flight_cmd 16 7] evicted=[flight_cmd 16 8] loss_mask=[flight_cmd 16 14]"
    foreach f $faults {
        puts "id=[dict get $f id] slot=[dict get $f slot] ready=[dict get $f ready] manual=[dict get $f manual] privilege=[dict get $f privilege] pc=0x[fase_hex [dict get $f pc] 16] cause=[dict get $f cause] cycle=[dict get $f cycle]"
    }
    return $faults
}
proc flight_dump {filename {id latest} {limit 0} {bank_mask 63}} {
    flight_require_v2
    if {$limit < 0 || $bank_mask < 1 || $bank_mask > 63} {error "Invalid dump limits"}
    if {$id eq "latest"} {
        set ready {}
        foreach f [flight_faults] {if {[dict get $f ready]} {lappend ready [dict get $f id]}}
        if {![llength $ready]} {error "No completed fault snapshot; use flight_capture for current execution"}
        set id [lindex $ready end]
    }
    # SELECT atomically leases this ID. If it was evicted, fail rather than read
    # another fault. Further faults cannot replace a leased snapshot.
    flight_cmd 18 0 $id
    set out ""
    set rc [catch {
        set slot -1
        for {set s 0} {$s < [flight_cmd 16 11]} {incr s} {
            if {[flight_cmd 17 1 $s] == $id && ([flight_cmd 17 0 $s] & 7) == 7} {set slot $s; break}
        }
        if {$slot < 0} {error "Selected snapshot not ready or lease missing"}
        set out [open $filename {WRONLY CREAT EXCL}]
        puts $out "# format=flow-flight-v2 id=$id slot=$slot flags=[flight_cmd 17 0 $slot]"
        set header {}
        for {set field 2} {$field < 10} {incr field} {lappend header [fase_hex [flight_cmd 17 $field $slot] 16]}
        puts $out "# fault_header=cycle,flags,pc,target,cause,tval,mstatus,satp:[join $header ,]"
        puts $out "# captures=[flight_cmd 16 7] evicted=[flight_cmd 16 8] loss_mask=[flight_cmd 16 14]"
        puts $out "bank,entry,phase,cycle,flags,word2,word3,word4,word5,word6,word7"
        for {set b 0} {$b < 6} {incr b} {
            if {!($bank_mask & (1 << $b))} {continue}
            set sizes [flight_cmd 17 [expr {10+$b}] $slot]
            set pre [expr {$sizes & 65535}]
            set count [expr {($sizes >> 16) & 65535}]
            set first [expr {$limit == 0 ? 0 : max(0, $count-$limit)}]
            puts $out "# bank=$b pre=$pre count=$count exported_from=$first"
            for {set e $first} {$e < $count} {incr e} {
                set row [list $b $e [expr {$e < $pre ? {pre} : {fault_or_post}}]]
                for {set w 0} {$w < 8} {incr w} {
                    lappend row [fase_hex [flight_cmd 19 0 [expr {($b << 13) | ($e << 3) | $w}]] 16]
                }
                puts $out [join $row ,]
            }
        }
    } message options]
    if {$out ne ""} {catch {close $out}}
    set release_rc [catch {flight_cmd 20} release_message]
    if {$rc} {return -options $options $message}
    if {$release_rc} {error "Saved $filename but lease release failed: $release_message"}
    return $filename
}
proc flight_capture {filename} {
    flight_require_v2
    set id [flight_cmd 21]
    # A manual capture reserves its lease immediately; ordinary faults continue
    # in the remaining slots. The 64-clock post window is much shorter than this.
    after 1
    return [flight_dump $filename $id]
}
proc flight_release {} {flight_require_v2; flight_cmd 20}

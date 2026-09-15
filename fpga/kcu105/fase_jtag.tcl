# Source in Vivado Tcl after open_hw_manager/connect_hw_server.
# The target must be opened with open_hw_target -jtag_mode.
# KU040 IR=6 bits, USER2=0x03 (Vivado 2022.2 device BSDL).
# Call fase_select with the measured TOTAL IR length and bits preceding KU040
# at TDO; other devices receive BYPASS. Defaults describe a KU040-only chain.
proc fase_hex {value digits} {
    set result ""
    for {set i 0} {$i < $digits} {incr i} {
        set result "[string index 0123456789abcdef [expr {$value & 15}]]$result"
        set value [expr {$value >> 4}]
    }
    return $result
}
proc fase_select {{ir_length 6} {ir_prefix 0} {bypass_prefix 0} {bypass_suffix 0}} {
    if {$ir_length < $ir_prefix + 6 || $ir_prefix < 0 || $bypass_prefix < 0 || $bypass_suffix < 0} {
        error "Invalid measured JTAG chain layout"
    }
    if {$bypass_prefix + $bypass_suffix > 16} { error "At most 16 BYPASS devices supported" }
    set ::fase_prefix $bypass_prefix
    set ::fase_suffix $bypass_suffix
    set pattern [expr {((1 << $ir_length) - 1) ^ (0x3f << $ir_prefix) ^ (3 << $ir_prefix)}]
    scan_ir_hw_jtag $ir_length -tdi [fase_hex $pattern [expr {($ir_length+3)/4}]]
}
proc fase_scan {frame} {
    if {![info exists ::fase_prefix]} { error "Call fase_select with the actual chain layout first" }
    set bits [expr {192 + $::fase_prefix + $::fase_suffix}]
    set raw [scan_dr_hw_jtag $bits -tdi [fase_hex [expr {$frame << $::fase_prefix}] [expr {($bits+3)/4}]]]
    set raw [string map {" " "" "\n" "" "\r" ""} $raw]
    regsub -nocase {^0x} $raw {} raw
    if {![regexp {^[0-9a-fA-F]+$} $raw]} { error "Unexpected TDO: $raw" }
    set raw_value [expr "0x$raw"]
    set result [expr {($raw_value >> $::fase_prefix) & ((1 << 192)-1)}]
    if {($result >> 176) != 0xfa5e || (($result >> 168) & 255) != 1} {
        error "FASE signature/version missing; check USER2, chain layout and bitstream"
    }
    return $result
}
proc fase_command {opcode {index 0} {data 0} {pc 0} {poll_limit 100}} {
    foreach {value width} [list $opcode 8 $index 6 $data 64 $pc 64] {
        if {$value < 0 || $value >= (1 << $width)} { error "FASE argument out of range" }
    }
    # Prime/reset synchronizers after FPGA configuration, then read status.
    set status [fase_scan 0]
    set status [fase_scan 0]
    if {($status >> 66) & 1} { error "FASE transaction still busy; do not resubmit" }
    if {![info exists ::fase_tag]} { set ::fase_tag 0 }
    set ::fase_tag [expr {($::fase_tag + 1) & 65535}]
    set frame [expr {(0xfa5e << 176) | ($::fase_tag << 142) | ($pc << 78) | ($data << 14) | ($index << 8) | $opcode}]
    fase_scan $frame
    for {set n 0} {$n < $poll_limit} {incr n} {
        set status [fase_scan 0]
        if {(($status >> 65) & 1) && (($status >> 68) & 65535) == $::fase_tag} {
            return [dict create error [expr {($status >> 64) & 1}] \
                data [expr {$status & ((1 << 64)-1)}] tag $::fase_tag \
                rejected_sticky [expr {($status >> 67) & 1}]]
        }
    }
    error "FASE response timeout; operation may already have executed, do not retry blindly"
}
# Examples: fase_command 0 ;# STATUS
#           fase_command 1 ;# HALT, then poll STATUS bits 2:1 == 3
#           fase_command 3 5 ;# READ_REG x5

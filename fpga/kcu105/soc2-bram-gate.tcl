# SOC-2 D5: this hook runs after synth_design and its reports, before opt/place.
set homes [get_cells -hierarchical -filter {REF_NAME =~ L2Home*}]
if {[llength $homes] == 0} { error "D5: no L2Home hierarchy after synthesis" }
set out [open soc2-l2-bram-gate.tsv w]
puts $out "hierarchy\tLUT\tFF\tRAMB36\tRAMB18"
foreach home $homes {
    set path [get_property NAME $home]
    set lut [llength [get_cells -hierarchical -filter "NAME =~ $path/* && REF_NAME =~ LUT*"]]
    set ff [llength [get_cells -hierarchical -filter "NAME =~ $path/* && REF_NAME =~ FD*"]]
    set b36 [llength [get_cells -hierarchical -filter "NAME =~ $path/* && REF_NAME =~ RAMB36*"]]
    set b18 [llength [get_cells -hierarchical -filter "NAME =~ $path/* && REF_NAME =~ RAMB18*"]]
    puts $out "$path\t$lut\t$ff\t$b36\t$b18"
    flush $out
    if {$b36 + $b18 == 0 || $lut >= 100000} {
        close $out
        error "D5: L2Home mapping failed: $path LUT=$lut RAMB36=$b36 RAMB18=$b18"
    }
    foreach array {data meta plruArr} {
        set blocks [get_cells -hierarchical -filter "NAME =~ $path/$array/* && (REF_NAME =~ RAMB36* || REF_NAME =~ RAMB18*)"]
        if {[llength $blocks] == 0} {
            close $out
            error "D5: $path/$array has no RAMB primitive"
        }
        puts $out "# $array\t[join $blocks ,]"
    }
}
close $out
puts "SOC2_D5_L2_BRAM_GATE_PASS"

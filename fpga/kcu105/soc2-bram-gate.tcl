# SOC-2 D5: after synth reports/checkpoint, before opt/place.
# Rebuilt hierarchy may hoist an array to the cluster. Accept it only when
# both read and write addresses trace exclusively to the owning L2Home.
proc d5_verify_owner {home blocks} {
    set addresses [get_pins -quiet -of_objects $blocks -filter {DIRECTION == IN && REF_PIN_NAME =~ ADDR*}]
    if {![llength $addresses]} { error "D5: RAM address collection empty for $home" }
    set roots [all_fanin -quiet -flat -to $addresses -startpoints_only]
    set owned [list]
    foreach root $roots {
        set cells [get_cells -quiet -of_objects $root]
        if {![llength $cells]} { error "D5: unowned RAM address port $root for $home" }
        foreach cell $cells {
            set ref [get_property REF_NAME $cell]
            if {$ref eq "GND" || $ref eq "VCC"} { continue }
            if {![string match "$home/*" [get_property NAME $cell]]} {
                error "D5: RAM address root $root is outside owner $home"
            }
            lappend owned $root
        }
    }
    if {![llength $owned]} { error "D5: no registered RAM address roots for $home" }
    return [lsort -unique $owned]
}
set homes [get_cells -hierarchical -filter {REF_NAME =~ L2Home*}]
if {[llength $homes] == 0} { error "D5: no L2Home hierarchy after synthesis" }
set out [open soc2-l2-bram-gate.tsv w]
puts $out "hierarchy\tLUT\tFF\tRAMB36\tRAMB18"
foreach home $homes {
    set path [get_property NAME $home]
    set scopes [list "$path/*"]
    foreach array {data meta plruArr} {
        set scope "$path/$array/*"
        set blocks [get_cells -quiet -hierarchical -filter "NAME =~ $scope && (REF_NAME =~ RAMB36* || REF_NAME =~ RAMB18*)"]
        if {![llength $blocks]} {
            set scope "[file dirname $path]/$array/ram/*"
            set blocks [get_cells -quiet -hierarchical -filter "NAME =~ $scope && (REF_NAME =~ RAMB36* || REF_NAME =~ RAMB18*)"]
            if {![llength $blocks]} {
                # Rebuilt hierarchy can lift a unique single-hart array all
                # the way to the design root. The same exclusive registered
                # address-owner check is mandatory for this fallback too.
                set scope "$array/ram/*"
                set blocks [get_cells -quiet -hierarchical -filter "NAME =~ $scope && (REF_NAME =~ RAMB36* || REF_NAME =~ RAMB18*)"]
            }
            if {![llength $blocks]} { close $out; error "D5: $path/$array has no RAMB primitive" }
            set roots [d5_verify_owner $path $blocks]
            lappend scopes $scope
            puts $out "# hoisted $array owner=$path address_roots=[join $roots ,]"
        }
        puts $out "# $array\t[join $blocks ,]"
    }
    set ownership [list]
    foreach scope $scopes { lappend ownership "NAME =~ $scope" }
    set selection "([join $ownership { || }])"
    set lut [llength [get_cells -hierarchical -filter "$selection && REF_NAME =~ LUT*"]]
    set ff [llength [get_cells -hierarchical -filter "$selection && REF_NAME =~ FD*"]]
    set b36 [llength [get_cells -hierarchical -filter "$selection && REF_NAME =~ RAMB36*"]]
    set b18 [llength [get_cells -hierarchical -filter "$selection && REF_NAME =~ RAMB18*"]]
    puts $out "$path\t$lut\t$ff\t$b36\t$b18"
    flush $out
    if {$b36 + $b18 == 0 || $lut >= 100000} {
        close $out
        error "D5: L2Home mapping failed: $path LUT=$lut RAMB36=$b36 RAMB18=$b18"
    }
}
close $out
puts "SOC2_D5_L2_BRAM_GATE_PASS"

if {$argc != 1} {
    error "usage: vivado -mode batch -source create_project.tcl -tclargs ABSOLUTE_OUTPUT"
}

set output_dir [file normalize [lindex $argv 0]]
if {[file pathtype $output_dir] ne "absolute"} {
    error "output directory must be absolute"
}
set script_dir [file normalize [file dirname [info script]]]
set project_dir [file join $output_dir vivado]

create_project -force pcie_only $project_dir -part xcku040-ffva1156-2-e
set_property target_language Verilog [current_project]
set_property simulator_language Mixed [current_project]
set_property source_mgmt_mode All [current_project]

add_files -norecurse [list \
    [file join $script_dir rtl PcieAxiDecerr.sv] \
    [file join $script_dir rtl PcieOnlyTop.sv]]
add_files -fileset constrs_1 -norecurse \
    [file join $script_dir constraints kcu105_pcie_only.xdc]
set_property top PcieOnlyTop [current_fileset]

create_ip -name xdma -vendor xilinx.com -library ip -version 4.1 \
    -module_name flow_xdma
set_property -dict [list \
    CONFIG.pl_link_cap_max_link_width X8 \
    CONFIG.pl_link_cap_max_link_speed 8.0_GT/s \
    CONFIG.axi_data_width 256_bit \
    CONFIG.axisten_freq 250 \
    CONFIG.xdma_axi_intf_mm AXI_Memory_Mapped \
    CONFIG.axilite_master_en true \
    CONFIG.axilite_master_size 64 \
    CONFIG.axilite_master_scale Kilobytes \
    CONFIG.xdma_rnum_chnl 1 \
    CONFIG.xdma_wnum_chnl 1 \
    CONFIG.pf0_device_id 9038 \
    CONFIG.enable_pcie_debug_ports True] [get_ips flow_xdma]

create_ip -name ila -vendor xilinx.com -library ip -version 6.2 \
    -module_name pcie_boot_ila
set_property -dict [list \
    CONFIG.C_NUM_OF_PROBES 8 \
    CONFIG.C_DATA_DEPTH 4096 \
    CONFIG.C_INPUT_PIPE_STAGES 2 \
    CONFIG.C_PROBE0_WIDTH 1 \
    CONFIG.C_PROBE1_WIDTH 1 \
    CONFIG.C_PROBE2_WIDTH 1 \
    CONFIG.C_PROBE3_WIDTH 1 \
    CONFIG.C_PROBE4_WIDTH 1 \
    CONFIG.C_PROBE5_WIDTH 8 \
    CONFIG.C_PROBE6_WIDTH 8 \
    CONFIG.C_PROBE7_WIDTH 8] [get_ips pcie_boot_ila]

create_ip -name ila -vendor xilinx.com -library ip -version 6.2 \
    -module_name pcie_link_ila
set_property -dict [list \
    CONFIG.C_NUM_OF_PROBES 12 \
    CONFIG.C_DATA_DEPTH 8192 \
    CONFIG.C_INPUT_PIPE_STAGES 2 \
    CONFIG.C_PROBE0_WIDTH 6 \
    CONFIG.C_PROBE1_WIDTH 4 \
    CONFIG.C_PROBE2_WIDTH 3 \
    CONFIG.C_PROBE3_WIDTH 1 \
    CONFIG.C_PROBE4_WIDTH 1 \
    CONFIG.C_PROBE5_WIDTH 1 \
    CONFIG.C_PROBE6_WIDTH 1 \
    CONFIG.C_PROBE7_WIDTH 1 \
    CONFIG.C_PROBE8_WIDTH 1 \
    CONFIG.C_PROBE9_WIDTH 5 \
    CONFIG.C_PROBE10_WIDTH 1 \
    CONFIG.C_PROBE11_WIDTH 8] [get_ips pcie_link_ila]

set diagnostic_ips [get_ips {flow_xdma pcie_boot_ila pcie_link_ila}]
generate_target all $diagnostic_ips
foreach ip $diagnostic_ips {
    set_property generate_synth_checkpoint false \
        [get_files [get_property IP_FILE $ip]]
}

update_compile_order -fileset sources_1
set_property strategy Flow_AreaOptimized_high [get_runs synth_1]
set_property strategy Performance_ExplorePostRoutePhysOpt [get_runs impl_1]

launch_runs synth_1 -jobs 8
wait_on_run synth_1
if {[get_property STATUS [get_runs synth_1]] ne "synth_design Complete!"} {
    error "synthesis failed: [get_property STATUS [get_runs synth_1]]"
}

launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} {
    error "implementation failed: [get_property STATUS [get_runs impl_1]]"
}

open_run impl_1
report_timing_summary -delay_type min_max -report_unconstrained -check_timing_verbose \
    -file [file join $output_dir timing_summary.rpt]
report_cdc -details -file [file join $output_dir cdc.rpt]
report_drc -file [file join $output_dir drc.rpt]
report_utilization -hierarchical -file [file join $output_dir utilization.rpt]
write_debug_probes -force [file join $output_dir pcie_only.ltx]

set bitstream [file join $project_dir pcie_only.runs impl_1 PcieOnlyTop.bit]
if {![file exists $bitstream]} {
    error "missing bitstream: $bitstream"
}
file copy -force $bitstream [file join $output_dir pcie_only.bit]

set setup_path [get_timing_paths -delay_type max -max_paths 1 -nworst 1]
set hold_path [get_timing_paths -delay_type min -max_paths 1 -nworst 1]
set wns [get_property SLACK $setup_path]
set whs [get_property SLACK $hold_path]
set status_file [open [file join $output_dir build-status.txt] w]
puts $status_file "SYNTH_STATUS=[get_property STATUS [get_runs synth_1]]"
puts $status_file "IMPL_STATUS=[get_property STATUS [get_runs impl_1]]"
puts $status_file "WNS=$wns"
puts $status_file "WHS=$whs"
close $status_file
puts "FLOW_PCIE_ONLY_BUILD WNS=$wns WHS=$whs BIT=$output_dir/pcie_only.bit"
if {$wns < 0.0 || $whs < 0.0} {
    error "timing failed: WNS=$wns WHS=$whs"
}

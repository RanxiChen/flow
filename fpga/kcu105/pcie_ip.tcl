# Vivado 2022.2 / KU040. A fresh build owns its generated IP.
create_ip -name xdma -vendor xilinx.com -library ip -version 4.1 -module_name flow_xdma
set_property -dict [list \
    CONFIG.pl_link_cap_max_link_width X8 \
    CONFIG.pl_link_cap_max_link_speed 8.0_GT/s \
    CONFIG.axi_data_width 256_bit CONFIG.axisten_freq 250 \
    CONFIG.xdma_axi_intf_mm AXI_Memory_Mapped \
    CONFIG.axilite_master_en true CONFIG.axilite_master_size 64 \
    CONFIG.axilite_master_scale Kilobytes \
    CONFIG.xdma_rnum_chnl 1 CONFIG.xdma_wnum_chnl 1 \
    CONFIG.pf0_device_id 9038] [get_ips flow_xdma]

foreach {name protocol width addr id} {
    flow_pcie_mm_cdc AXI4 256 64 4
    flow_pcie_ctl_cdc AXI4LITE 32 32 0
} {
    create_ip -name axi_clock_converter -vendor xilinx.com -library ip -version 2.1 -module_name $name
    set_property -dict [list CONFIG.PROTOCOL $protocol CONFIG.DATA_WIDTH $width \
        CONFIG.ADDR_WIDTH $addr CONFIG.ID_WIDTH $id CONFIG.ACLK_ASYNC 1 \
        CONFIG.SYNCHRONIZATION_STAGES 3] [get_ips $name]
}
set flow_pcie_ips [get_ips {flow_xdma flow_pcie_mm_cdc flow_pcie_ctl_cdc}]
generate_target all $flow_pcie_ips
# Synthesize IP along with the top so all source/checkpoint inputs are build-local.
foreach ip $flow_pcie_ips {
    set_property GENERATE_SYNTH_CHECKPOINT false [get_files [get_property IP_FILE $ip]]
}
report_property [get_ips flow_xdma] -file flow-xdma-properties.rpt

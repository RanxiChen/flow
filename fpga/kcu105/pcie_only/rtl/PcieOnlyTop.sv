`timescale 1ns/1ps

module PcieOnlyTop (
    input  wire       clk125_p,
    input  wire       clk125_n,
    input  wire       pcie_x8_clk_p,
    input  wire       pcie_x8_clk_n,
    input  wire       pcie_x8_rst_n,
    input  wire [7:0] pcie_x8_rx_p,
    input  wire [7:0] pcie_x8_rx_n,
    output wire [7:0] pcie_x8_tx_p,
    output wire [7:0] pcie_x8_tx_n
);
    wire clk125_ibuf;
    wire clk125;
    wire pcie_refclk;
    wire pcie_refclk_gt;
    wire pcie_refclk_fabric;
    wire axi_aclk;
    wire axi_aresetn;
    wire user_lnk_up;

    IBUFDS #(
        .DIFF_TERM("TRUE"),
        .IBUF_LOW_PWR("FALSE")
    ) clk125_input (
        .I(clk125_p),
        .IB(clk125_n),
        .O(clk125_ibuf)
    );
    BUFG clk125_global (.I(clk125_ibuf), .O(clk125));

    IBUFDS_GTE3 pcie_refclk_input (
        .CEB(1'b0),
        .I(pcie_x8_clk_p),
        .IB(pcie_x8_clk_n),
        .O(pcie_refclk_gt),
        .ODIV2(pcie_refclk)
    );

    // ODIV2 is a GT clock-network output.  XDMA consumes it directly as its
    // sys_clk, but ordinary fabric logic must reach it through BUFG_GT.
    BUFG_GT pcie_refclk_fabric_buffer (
        .I(pcie_refclk),
        .CE(1'b1),
        .CEMASK(1'b0),
        .CLR(1'b0),
        .CLRMASK(1'b0),
        .DIV(3'b000),
        .O(pcie_refclk_fabric)
    );

    // Clock-existence monitors.  A divided counter bit is synchronized into
    // clk125; no raw clock is ever treated as data by the ILA.
    reg [7:0] refclk_heartbeat_counter = 8'b0;
    reg [7:0] axi_heartbeat_counter = 8'b0;
    always @(posedge pcie_refclk_fabric)
        refclk_heartbeat_counter <= refclk_heartbeat_counter + 1'b1;
    always @(posedge axi_aclk)
        axi_heartbeat_counter <= axi_heartbeat_counter + 1'b1;

    wire perst_n_sync;
    wire refclk_toggle_sync;
    wire axi_toggle_sync;
    wire axi_aresetn_sync;
    wire link_up_sync;
    wire perst_n_axi_sync;

    xpm_cdc_single #(
        .DEST_SYNC_FF(3), .INIT_SYNC_FF(1), .SIM_ASSERT_CHK(0), .SRC_INPUT_REG(0)
    ) sync_perst (
        .src_clk(clk125), .src_in(pcie_x8_rst_n),
        .dest_clk(clk125), .dest_out(perst_n_sync)
    );
    xpm_cdc_single #(
        .DEST_SYNC_FF(3), .INIT_SYNC_FF(1), .SIM_ASSERT_CHK(0), .SRC_INPUT_REG(0)
    ) sync_refclk_toggle (
        .src_clk(pcie_refclk_fabric), .src_in(refclk_heartbeat_counter[7]),
        .dest_clk(clk125), .dest_out(refclk_toggle_sync)
    );
    xpm_cdc_single #(
        .DEST_SYNC_FF(3), .INIT_SYNC_FF(1), .SIM_ASSERT_CHK(0), .SRC_INPUT_REG(0)
    ) sync_axi_toggle (
        .src_clk(axi_aclk), .src_in(axi_heartbeat_counter[7]),
        .dest_clk(clk125), .dest_out(axi_toggle_sync)
    );
    xpm_cdc_single #(
        .DEST_SYNC_FF(3), .INIT_SYNC_FF(1), .SIM_ASSERT_CHK(0), .SRC_INPUT_REG(0)
    ) sync_axi_reset (
        .src_clk(axi_aclk), .src_in(axi_aresetn),
        .dest_clk(clk125), .dest_out(axi_aresetn_sync)
    );
    xpm_cdc_single #(
        .DEST_SYNC_FF(3), .INIT_SYNC_FF(1), .SIM_ASSERT_CHK(0), .SRC_INPUT_REG(0)
    ) sync_link_up (
        .src_clk(axi_aclk), .src_in(user_lnk_up),
        .dest_clk(clk125), .dest_out(link_up_sync)
    );
    xpm_cdc_single #(
        .DEST_SYNC_FF(3), .INIT_SYNC_FF(1), .SIM_ASSERT_CHK(0), .SRC_INPUT_REG(0)
    ) sync_perst_to_axi (
        .src_clk(clk125), .src_in(pcie_x8_rst_n),
        .dest_clk(axi_aclk), .dest_out(perst_n_axi_sync)
    );

    reg previous_perst_n = 1'b0;
    reg previous_refclk_toggle = 1'b0;
    reg previous_axi_toggle = 1'b0;
    reg perst_seen_low = 1'b0;
    reg perst_seen_high = 1'b0;
    reg refclk_seen = 1'b0;
    reg axi_clk_seen = 1'b0;
    reg link_up_seen = 1'b0;
    reg [7:0] perst_rise_count = 8'b0;
    reg [7:0] perst_fall_count = 8'b0;
    always @(posedge clk125) begin
        previous_perst_n <= perst_n_sync;
        previous_refclk_toggle <= refclk_toggle_sync;
        previous_axi_toggle <= axi_toggle_sync;
        if (!perst_n_sync)
            perst_seen_low <= 1'b1;
        if (perst_n_sync)
            perst_seen_high <= 1'b1;
        if (perst_n_sync && !previous_perst_n)
            perst_rise_count <= perst_rise_count + 1'b1;
        if (!perst_n_sync && previous_perst_n)
            perst_fall_count <= perst_fall_count + 1'b1;
        if (refclk_toggle_sync != previous_refclk_toggle)
            refclk_seen <= 1'b1;
        if (axi_toggle_sync != previous_axi_toggle)
            axi_clk_seen <= 1'b1;
        if (link_up_sync)
            link_up_seen <= 1'b1;
    end

    // XDMA PCIe debug ports.  These are enabled explicitly by create_project.tcl.
    wire [3:0] cfg_negotiated_width;
    wire [2:0] cfg_current_speed;
    wire [5:0] cfg_ltssm_state;
    wire cfg_err_cor;
    wire cfg_err_fatal;
    wire cfg_err_nonfatal;
    wire [4:0] cfg_local_error;
    wire cfg_local_error_valid;

    // AXI4 master wires into the safe DECERR sink.
    wire m_axi_awready, m_axi_wready, m_axi_bvalid, m_axi_arready;
    wire m_axi_rvalid, m_axi_rlast;
    wire [3:0] m_axi_bid, m_axi_rid;
    wire [1:0] m_axi_bresp, m_axi_rresp;
    wire [255:0] m_axi_rdata;
    wire [3:0] m_axi_awid, m_axi_arid;
    wire [63:0] m_axi_awaddr, m_axi_araddr;
    wire [7:0] m_axi_awlen, m_axi_arlen;
    wire [2:0] m_axi_awsize, m_axi_arsize;
    wire [1:0] m_axi_awburst, m_axi_arburst;
    wire [2:0] m_axi_awprot, m_axi_arprot;
    wire m_axi_awvalid, m_axi_awlock, m_axi_wlast, m_axi_wvalid;
    wire m_axi_bready, m_axi_arvalid, m_axi_arlock, m_axi_rready;
    wire [3:0] m_axi_awcache, m_axi_arcache;
    wire [255:0] m_axi_wdata;
    wire [31:0] m_axi_wstrb;

    // AXI4-Lite master wires into the safe DECERR sink.
    wire [31:0] m_axil_awaddr, m_axil_wdata, m_axil_araddr, m_axil_rdata;
    wire [2:0] m_axil_awprot, m_axil_arprot;
    wire [3:0] m_axil_wstrb;
    wire m_axil_awvalid, m_axil_awready, m_axil_wvalid, m_axil_wready;
    wire m_axil_bvalid, m_axil_bready, m_axil_arvalid, m_axil_arready;
    wire m_axil_rvalid, m_axil_rready;
    wire [1:0] m_axil_bresp, m_axil_rresp;

    flow_xdma xdma (
        .sys_clk(pcie_refclk),
        .sys_clk_gt(pcie_refclk_gt),
        .sys_rst_n(pcie_x8_rst_n),
        .user_lnk_up(user_lnk_up),
        .pci_exp_txp(pcie_x8_tx_p), .pci_exp_txn(pcie_x8_tx_n),
        .pci_exp_rxp(pcie_x8_rx_p), .pci_exp_rxn(pcie_x8_rx_n),
        .axi_aclk(axi_aclk), .axi_aresetn(axi_aresetn),
        .usr_irq_req(1'b0),
        .cfg_negotiated_width_o(cfg_negotiated_width),
        .cfg_current_speed_o(cfg_current_speed),
        .cfg_ltssm_state_o(cfg_ltssm_state),
        .cfg_err_cor_o(cfg_err_cor),
        .cfg_err_fatal_o(cfg_err_fatal),
        .cfg_err_nonfatal_o(cfg_err_nonfatal),
        .cfg_local_error_o(cfg_local_error),
        .cfg_local_error_valid_o(cfg_local_error_valid),

        .m_axi_awready(m_axi_awready), .m_axi_wready(m_axi_wready),
        .m_axi_bid(m_axi_bid), .m_axi_bresp(m_axi_bresp), .m_axi_bvalid(m_axi_bvalid),
        .m_axi_arready(m_axi_arready), .m_axi_rid(m_axi_rid),
        .m_axi_rdata(m_axi_rdata), .m_axi_rresp(m_axi_rresp),
        .m_axi_rlast(m_axi_rlast), .m_axi_rvalid(m_axi_rvalid),
        .m_axi_awid(m_axi_awid), .m_axi_awaddr(m_axi_awaddr),
        .m_axi_awlen(m_axi_awlen), .m_axi_awsize(m_axi_awsize),
        .m_axi_awburst(m_axi_awburst), .m_axi_awprot(m_axi_awprot),
        .m_axi_awvalid(m_axi_awvalid), .m_axi_awlock(m_axi_awlock),
        .m_axi_awcache(m_axi_awcache), .m_axi_wdata(m_axi_wdata),
        .m_axi_wstrb(m_axi_wstrb), .m_axi_wlast(m_axi_wlast),
        .m_axi_wvalid(m_axi_wvalid), .m_axi_bready(m_axi_bready),
        .m_axi_arid(m_axi_arid), .m_axi_araddr(m_axi_araddr),
        .m_axi_arlen(m_axi_arlen), .m_axi_arsize(m_axi_arsize),
        .m_axi_arburst(m_axi_arburst), .m_axi_arprot(m_axi_arprot),
        .m_axi_arvalid(m_axi_arvalid), .m_axi_arlock(m_axi_arlock),
        .m_axi_arcache(m_axi_arcache), .m_axi_rready(m_axi_rready),

        .m_axil_awaddr(m_axil_awaddr), .m_axil_awprot(m_axil_awprot),
        .m_axil_awvalid(m_axil_awvalid), .m_axil_awready(m_axil_awready),
        .m_axil_wdata(m_axil_wdata), .m_axil_wstrb(m_axil_wstrb),
        .m_axil_wvalid(m_axil_wvalid), .m_axil_wready(m_axil_wready),
        .m_axil_bvalid(m_axil_bvalid), .m_axil_bresp(m_axil_bresp),
        .m_axil_bready(m_axil_bready), .m_axil_araddr(m_axil_araddr),
        .m_axil_arprot(m_axil_arprot), .m_axil_arvalid(m_axil_arvalid),
        .m_axil_arready(m_axil_arready), .m_axil_rdata(m_axil_rdata),
        .m_axil_rresp(m_axil_rresp), .m_axil_rvalid(m_axil_rvalid),
        .m_axil_rready(m_axil_rready),

        .cfg_mgmt_addr(19'b0), .cfg_mgmt_write(1'b0),
        .cfg_mgmt_write_data(32'b0), .cfg_mgmt_byte_enable(4'b0),
        .cfg_mgmt_read(1'b0), .cfg_mgmt_type1_cfg_reg_access(1'b0)
    );

    PcieAxiDecerr axi_decerr (
        .clk(axi_aclk), .reset_n(axi_aresetn),
        .s_awid(m_axi_awid), .s_awlen(m_axi_awlen),
        .s_awvalid(m_axi_awvalid), .s_awready(m_axi_awready),
        .s_wdata(m_axi_wdata), .s_wstrb(m_axi_wstrb),
        .s_wlast(m_axi_wlast), .s_wvalid(m_axi_wvalid), .s_wready(m_axi_wready),
        .s_bid(m_axi_bid), .s_bresp(m_axi_bresp),
        .s_bvalid(m_axi_bvalid), .s_bready(m_axi_bready),
        .s_arid(m_axi_arid), .s_arlen(m_axi_arlen),
        .s_arvalid(m_axi_arvalid), .s_arready(m_axi_arready),
        .s_rid(m_axi_rid), .s_rdata(m_axi_rdata), .s_rresp(m_axi_rresp),
        .s_rlast(m_axi_rlast), .s_rvalid(m_axi_rvalid), .s_rready(m_axi_rready)
    );

    PcieAxilDecerr axil_decerr (
        .clk(axi_aclk), .reset_n(axi_aresetn),
        .s_awaddr(m_axil_awaddr), .s_awprot(m_axil_awprot),
        .s_awvalid(m_axil_awvalid), .s_awready(m_axil_awready),
        .s_wdata(m_axil_wdata), .s_wstrb(m_axil_wstrb),
        .s_wvalid(m_axil_wvalid), .s_wready(m_axil_wready),
        .s_bresp(m_axil_bresp), .s_bvalid(m_axil_bvalid), .s_bready(m_axil_bready),
        .s_araddr(m_axil_araddr), .s_arprot(m_axil_arprot),
        .s_arvalid(m_axil_arvalid), .s_arready(m_axil_arready),
        .s_rdata(m_axil_rdata), .s_rresp(m_axil_rresp),
        .s_rvalid(m_axil_rvalid), .s_rready(m_axil_rready)
    );

    wire [7:0] boot_sticky = {link_up_seen, axi_clk_seen, refclk_seen,
                              perst_seen_high, perst_seen_low,
                              axi_aresetn_sync, link_up_sync, perst_n_sync};
    wire [7:0] axi_activity = {m_axil_rvalid, m_axil_arvalid,
                               m_axil_bvalid, m_axil_wvalid,
                               m_axi_rvalid, m_axi_arvalid,
                               m_axi_bvalid, m_axi_awvalid};

    pcie_boot_ila boot_ila (
        .clk(clk125),
        .probe0(perst_n_sync),
        .probe1(refclk_toggle_sync),
        .probe2(axi_toggle_sync),
        .probe3(axi_aresetn_sync),
        .probe4(link_up_sync),
        .probe5(boot_sticky),
        .probe6(perst_rise_count),
        .probe7(perst_fall_count)
    );

    pcie_link_ila link_ila (
        .clk(axi_aclk),
        .probe0(cfg_ltssm_state),
        .probe1(cfg_negotiated_width),
        .probe2(cfg_current_speed),
        .probe3(user_lnk_up),
        .probe4(axi_aresetn),
        .probe5(perst_n_axi_sync),
        .probe6(cfg_err_cor),
        .probe7(cfg_err_nonfatal),
        .probe8(cfg_err_fatal),
        .probe9(cfg_local_error),
        .probe10(cfg_local_error_valid),
        .probe11(axi_activity)
    );

    // These protocol fields are intentionally irrelevant to a DECERR sink.
    wire unused_axi_fields = ^{m_axi_awaddr, m_axi_awsize, m_axi_awburst,
                               m_axi_awprot, m_axi_awlock, m_axi_awcache,
                               m_axi_araddr, m_axi_arsize, m_axi_arburst,
                               m_axi_arprot, m_axi_arlock, m_axi_arcache};
endmodule

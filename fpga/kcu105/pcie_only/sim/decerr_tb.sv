`timescale 1ns/1ps

module decerr_tb;
    reg clk = 0;
    reg reset_n = 0;
    always #5 clk = ~clk;
    initial begin #100000; $fatal(1, "timeout"); end

    reg [3:0] awid = 0;
    reg [7:0] awlen = 0;
    reg awvalid = 0;
    wire awready;
    reg [255:0] wdata = 0;
    reg [31:0] wstrb = 0;
    reg wlast = 0;
    reg wvalid = 0;
    wire wready;
    wire [3:0] bid;
    wire [1:0] bresp;
    wire bvalid;
    reg bready = 0;
    reg [3:0] arid = 0;
    reg [7:0] arlen = 0;
    reg arvalid = 0;
    wire arready;
    wire [3:0] rid;
    wire [255:0] rdata;
    wire [1:0] rresp;
    wire rlast;
    wire rvalid;
    reg rready = 0;

    PcieAxiDecerr mm (
        .clk, .reset_n,
        .s_awid(awid), .s_awlen(awlen), .s_awvalid(awvalid), .s_awready(awready),
        .s_wdata(wdata), .s_wstrb(wstrb), .s_wlast(wlast),
        .s_wvalid(wvalid), .s_wready(wready),
        .s_bid(bid), .s_bresp(bresp), .s_bvalid(bvalid), .s_bready(bready),
        .s_arid(arid), .s_arlen(arlen), .s_arvalid(arvalid), .s_arready(arready),
        .s_rid(rid), .s_rdata(rdata), .s_rresp(rresp), .s_rlast(rlast),
        .s_rvalid(rvalid), .s_rready(rready)
    );

    reg [31:0] lawaddr = 0, lwdata = 0, laraddr = 0;
    reg [2:0] lawprot = 0, larprot = 0;
    reg [3:0] lwstrb = 0;
    reg lawvalid = 0, lwvalid = 0, lbready = 0;
    reg larvalid = 0, lrready = 0;
    wire lawready, lwready, lbvalid, larready, lrvalid;
    wire [1:0] lbresp, lrresp;
    wire [31:0] lrdata;
    PcieAxilDecerr lite (
        .clk, .reset_n,
        .s_awaddr(lawaddr), .s_awprot(lawprot), .s_awvalid(lawvalid), .s_awready(lawready),
        .s_wdata(lwdata), .s_wstrb(lwstrb), .s_wvalid(lwvalid), .s_wready(lwready),
        .s_bresp(lbresp), .s_bvalid(lbvalid), .s_bready(lbready),
        .s_araddr(laraddr), .s_arprot(larprot), .s_arvalid(larvalid), .s_arready(larready),
        .s_rdata(lrdata), .s_rresp(lrresp), .s_rvalid(lrvalid), .s_rready(lrready)
    );

    task tick; begin @(posedge clk); #1; end endtask
    integer beats;
    initial begin
        repeat (3) tick();
        reset_n = 1;
        tick();

        awid = 4'ha; awlen = 1; awvalid = 1;
        if (!awready) $fatal(1, "AW not accepted");
        tick();
        awvalid = 0;
        wvalid = 1; wlast = 0;
        if (!wready) $fatal(1, "first W not accepted");
        tick();
        wlast = 1; tick();
        wvalid = 0; wlast = 0;
        if (!bvalid || bid != 4'ha || bresp != 2'b11)
            $fatal(1, "bad write DECERR response");
        bready = 1; tick(); bready = 0;

        arid = 4'h5; arlen = 2; arvalid = 1;
        if (!arready) $fatal(1, "AR not accepted");
        tick();
        arvalid = 0; rready = 1; beats = 0;
        while (beats < 3) begin
            if (rvalid) begin
                if (rid != 4'h5 || rresp != 2'b11 || rdata != 0)
                    $fatal(1, "bad read DECERR beat");
                if (rlast != (beats == 2)) $fatal(1, "bad RLAST beat=%0d", beats);
                beats = beats + 1;
            end
            tick();
        end
        rready = 0;

        // AXI-Lite permits data before address.
        lwdata = 32'h12345678; lwstrb = 4'hf; lwvalid = 1; tick(); lwvalid = 0;
        lawaddr = 32'h40; lawvalid = 1; tick(); lawvalid = 0;
        tick();
        if (!lbvalid || lbresp != 2'b11) $fatal(1, "bad AXI-Lite write response");
        lbready = 1; tick(); lbready = 0;

        laraddr = 32'h80; larvalid = 1; tick(); larvalid = 0;
        if (!lrvalid || lrresp != 2'b11 || lrdata != 0)
            $fatal(1, "bad AXI-Lite read response");
        lrready = 1; tick(); lrready = 0;

        $display("PCIE_ONLY_DECERR_PASS");
        $finish;
    end
endmodule

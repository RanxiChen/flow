`default_nettype none

module flow_plic_tb;
    localparam [31:0] PLIC_BASE = 32'h0c00_0000;

    reg clk;
    reg rst = 1'b1;
    initial clk = 1'b0;
    always #5 clk = ~clk;

    reg [31:0] wb_adr = 32'b0;
    reg [63:0] wb_dat_w = 64'b0;
    wire [63:0] wb_dat_r;
    reg [7:0] wb_sel = 8'b0;
    reg wb_cyc = 1'b0;
    reg wb_stb = 1'b0;
    wire wb_ack;
    reg wb_we = 1'b0;
    wire wb_err;
    reg [30:0] sources = 31'b0;
    wire [3:0] meip;
    wire [3:0] seip;
    wire [31:0] debug_pending;
    wire [255:0] debug_claims;
    wire [92:0] debug_priorities;
    wire [255:0] debug_enables;
    wire [23:0] debug_thresholds;

    FlowPlic #(.NUM_HARTS(4), .NUM_SOURCES(31)) dut (
        .clk(clk), .rst(rst),
        .wb_adr(wb_adr), .wb_dat_w(wb_dat_w), .wb_dat_r(wb_dat_r),
        .wb_sel(wb_sel), .wb_cyc(wb_cyc), .wb_stb(wb_stb),
        .wb_ack(wb_ack), .wb_we(wb_we), .wb_err(wb_err),
        .sources(sources), .meip(meip), .seip(seip),
        .debug_pending(debug_pending), .debug_claims(debug_claims),
        .debug_priorities(debug_priorities), .debug_enables(debug_enables),
        .debug_thresholds(debug_thresholds)
    );

    task automatic wb_write32(input [31:0] byte_address, input [31:0] value);
        begin
            @(negedge clk);
            wb_adr = {3'b000, byte_address[31:3]};
            wb_dat_w = byte_address[2] ? {value, 32'b0} : {32'b0, value};
            wb_sel = byte_address[2] ? 8'hf0 : 8'h0f;
            wb_we = 1'b1;
            wb_cyc = 1'b1;
            wb_stb = 1'b1;
            while (!wb_ack) @(negedge clk);
            wb_cyc = 1'b0;
            wb_stb = 1'b0;
            wb_we = 1'b0;
            wb_sel = 8'b0;
            @(negedge clk);
        end
    endtask

    task automatic wb_read32(input [31:0] byte_address, output [31:0] value);
        begin
            @(negedge clk);
            wb_adr = {3'b000, byte_address[31:3]};
            wb_dat_w = 64'b0;
            wb_sel = byte_address[2] ? 8'hf0 : 8'h0f;
            wb_we = 1'b0;
            wb_cyc = 1'b1;
            wb_stb = 1'b1;
            while (!wb_ack) @(negedge clk);
            value = byte_address[2] ? wb_dat_r[63:32] : wb_dat_r[31:0];
            wb_cyc = 1'b0;
            wb_stb = 1'b0;
            wb_sel = 8'b0;
            @(negedge clk);
        end
    endtask

    task automatic expect32(
        input [31:0] actual, input [31:0] expected, input [255:0] label);
        begin
            if (actual !== expected) begin
                $display("[FLOW-PLIC-FAIL] %0s actual=0x%08x expected=0x%08x",
                    label, actual, expected);
                $fatal(1);
            end
        end
    endtask

    reg [31:0] value;
    initial begin
        repeat (4) @(negedge clk);
        rst = 1'b0;
        repeat (2) @(negedge clk);

        // The test intentionally uses global LiteX addresses.  The standalone
        // RTL must localize the decoded slave address internally.
        wb_write32(PLIC_BASE + 32'h0000_0028, 32'd1);      // priority[10].
        wb_read32(PLIC_BASE + 32'h0000_0028, value);
        expect32(value, 32'd1, "priority10 readback");

        wb_write32(PLIC_BASE + 32'h0000_2000, 32'h0000_0400); // M0 enable[10].
        wb_read32(PLIC_BASE + 32'h0000_2000, value);
        expect32(value, 32'h0000_0400, "M0 enable readback");

        wb_write32(PLIC_BASE + 32'h0020_0000, 32'd0);      // M0 threshold.
        wb_read32(PLIC_BASE + 32'h0020_0000, value);
        expect32(value, 32'd0, "M0 threshold readback");

        sources[9] = 1'b1;
        repeat (3) @(negedge clk);
        if (!debug_pending[10] || !meip[0]) begin
            $display("[FLOW-PLIC-FAIL] source10 did not reach pending/MEIP");
            $fatal(1);
        end

        wb_read32(PLIC_BASE + 32'h0020_0004, value);
        expect32(value, 32'd10, "M0 claim source10");
        if (debug_pending[10] || meip[0]) begin
            $display("[FLOW-PLIC-FAIL] claim did not clear source10 pending");
            $fatal(1);
        end

        // A level-high source must not re-pend before completion.
        repeat (3) @(negedge clk);
        if (debug_pending[10] || meip[0]) begin
            $display("[FLOW-PLIC-FAIL] source10 re-pended before complete");
            $fatal(1);
        end

        wb_write32(PLIC_BASE + 32'h0020_0004, 32'd10);
        repeat (3) @(negedge clk);
        if (!debug_pending[10] || !meip[0]) begin
            $display("[FLOW-PLIC-FAIL] level source10 did not re-pend after complete");
            $fatal(1);
        end

        sources[9] = 1'b0;
        wb_read32(PLIC_BASE + 32'h0020_0004, value);
        expect32(value, 32'd10, "second M0 claim source10");
        wb_write32(PLIC_BASE + 32'h0020_0004, 32'd10);

        // Supervisor context 0 is context 1.
        wb_write32(PLIC_BASE + 32'h0000_002c, 32'd2);      // priority[11].
        wb_write32(PLIC_BASE + 32'h0000_2080, 32'h0000_0800); // S0 enable[11].
        wb_write32(PLIC_BASE + 32'h0020_1000, 32'd1);      // S0 threshold.
        sources[10] = 1'b1;
        repeat (3) @(negedge clk);
        if (!seip[0] || meip[0]) begin
            $display("[FLOW-PLIC-FAIL] source11 did not route only to S0");
            $fatal(1);
        end
        wb_read32(PLIC_BASE + 32'h0020_1004, value);
        expect32(value, 32'd11, "S0 claim source11");
        sources[10] = 1'b0;
        wb_write32(PLIC_BASE + 32'h0020_1004, 32'd11);

        // Machine context for hart 1 is context 2.
        wb_write32(PLIC_BASE + 32'h0000_0030, 32'd1);      // priority[12].
        wb_write32(PLIC_BASE + 32'h0000_2100, 32'h0000_1000); // M1 enable[12].
        wb_write32(PLIC_BASE + 32'h0020_2000, 32'd0);
        sources[11] = 1'b1;
        repeat (3) @(negedge clk);
        if (!meip[1] || meip[0]) begin
            $display("[FLOW-PLIC-FAIL] source12 did not route only to M1");
            $fatal(1);
        end

        $display("[FLOW-PLIC-PASS]");
        $finish;
    end
endmodule

`default_nettype wire

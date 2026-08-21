`default_nettype none

module flow_clint_tb;
    localparam [31:0] CLINT_BASE = 32'h0200_0000;

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
    wire [3:0] msip;
    wire [3:0] mtip;
    wire [63:0] mtime;
    wire [3:0] debug_msip;
    wire [255:0] debug_mtimecmp;

    FlowClint #(
        .NUM_HARTS(4),
        .SYS_CLK_FREQ(4),
        .TIMEBASE_FREQ(1),
        .REGION_BYTES(65536)
    ) dut (
        .clk(clk), .rst(rst),
        .wb_adr(wb_adr), .wb_dat_w(wb_dat_w), .wb_dat_r(wb_dat_r),
        .wb_sel(wb_sel), .wb_cyc(wb_cyc), .wb_stb(wb_stb),
        .wb_ack(wb_ack), .wb_we(wb_we), .wb_err(wb_err),
        .msip(msip), .mtip(mtip), .mtime(mtime),
        .debug_msip(debug_msip), .debug_mtimecmp(debug_mtimecmp)
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

    task automatic wb_write64(input [31:0] byte_address, input [63:0] value);
        begin
            @(negedge clk);
            wb_adr = {3'b000, byte_address[31:3]};
            wb_dat_w = value;
            wb_sel = 8'hff;
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

    task automatic wb_read64(input [31:0] byte_address, output [63:0] value);
        begin
            @(negedge clk);
            wb_adr = {3'b000, byte_address[31:3]};
            wb_dat_w = 64'b0;
            wb_sel = 8'hff;
            wb_we = 1'b0;
            wb_cyc = 1'b1;
            wb_stb = 1'b1;
            while (!wb_ack) @(negedge clk);
            value = wb_dat_r;
            wb_cyc = 1'b0;
            wb_stb = 1'b0;
            wb_sel = 8'b0;
            @(negedge clk);
        end
    endtask

    task automatic expect64(
        input [63:0] actual, input [63:0] expected, input [255:0] label);
        begin
            if (actual !== expected) begin
                $display("[FLOW-CLINT-FAIL] %0s actual=0x%016x expected=0x%016x",
                    label, actual, expected);
                $fatal(1);
            end
        end
    endtask

    reg [63:0] value;
    reg [63:0] timer_start;
    initial begin
        repeat (4) @(negedge clk);
        rst = 1'b0;
        repeat (2) @(negedge clk);

        // Global LiteX addresses must be localized inside the decoded slave.
        wb_read64(CLINT_BASE + 32'h0000_4000, value);
        expect64(value, 64'hffff_ffff_ffff_ffff, "mtimecmp0 reset");

        // msip[0] and msip[1] share one word but use independent byte lanes.
        wb_write32(CLINT_BASE + 32'h0000_0000, 32'd1);
        if (msip !== 4'b0001) begin
            $display("[FLOW-CLINT-FAIL] msip0 write or lane isolation failed");
            $fatal(1);
        end
        wb_write32(CLINT_BASE + 32'h0000_0004, 32'd1);
        if (msip !== 4'b0011) begin
            $display("[FLOW-CLINT-FAIL] msip1 write or lane isolation failed");
            $fatal(1);
        end
        wb_write32(CLINT_BASE + 32'h0000_0000, 32'd0);
        wb_write32(CLINT_BASE + 32'h0000_0004, 32'd0);
        if (msip !== 4'b0000) begin
            $display("[FLOW-CLINT-FAIL] msip clear failed");
            $fatal(1);
        end

        // Partial writes preserve unselected mtimecmp bytes.
        wb_write32(CLINT_BASE + 32'h0000_4000, 32'h1234_5678);
        wb_read64(CLINT_BASE + 32'h0000_4000, value);
        expect64(value, 64'hffff_ffff_1234_5678, "mtimecmp partial write");

        wb_read64(CLINT_BASE + 32'h0000_bff8, timer_start);
        wb_write64(CLINT_BASE + 32'h0000_4000, timer_start + 64'd6);
        while (!mtip[0]) @(negedge clk);
        if (mtip !== 4'b0001) begin
            $display("[FLOW-CLINT-FAIL] per-hart MTIP routing failed");
            $fatal(1);
        end
        wb_write64(CLINT_BASE + 32'h0000_4000, 64'hffff_ffff_ffff_ffff);
        if (mtip[0]) begin
            $display("[FLOW-CLINT-FAIL] MTIP did not clear after compare update");
            $fatal(1);
        end

        wb_read64(CLINT_BASE + 32'h0000_8000, value);
        expect64(value, 64'b0, "unimplemented offset RAZ");

        $display("[FLOW-CLINT-PASS]");
        $finish;
    end

    initial begin
        #100000;
        $display("[FLOW-CLINT-FAIL] watchdog timeout");
        $fatal(1);
    end
endmodule

`default_nettype wire

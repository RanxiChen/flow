`timescale 1ns/1ps
module control_tb;
reg  clk = 0;
reg  reset = 0;
reg [31:0] s_awaddr = 0;
reg [31:0] s_araddr = 0;
reg [31:0] s_wdata = 0;
reg [3:0] s_wstrb = 0;
reg  s_awvalid = 0;
reg  s_wvalid = 0;
reg  s_arvalid = 0;
reg  s_bready = 0;
reg  s_rready = 0;
wire  s_awready;
wire  s_wready;
wire  s_arready;
wire  s_bvalid;
wire  s_rvalid;
wire [1:0] s_bresp;
wire [1:0] s_rresp;
wire [31:0] s_rdata;
wire  cmd_valid;
reg  cmd_ready = 0;
wire [7:0] cmd_opcode;
wire [5:0] cmd_index;
wire [63:0] cmd_data;
wire [63:0] cmd_pc;
reg  rsp_valid = 0;
reg  rsp_error = 0;
reg [63:0] rsp_data = 0;
wire  rsp_ready;
reg [31:0] read_words = 0;
reg [31:0] write_words = 0;
reg [31:0] memory_errors = 0;
FlowPcieControl dut (.*);
always #5 clk = ~clk;
initial begin #2000000; $fatal(1,"timeout"); end
task tick; begin @(posedge clk); #1; end endtask
task write_reg(input [31:0] addr, value,input [3:0] strb,input bit fail,input bit data_first);
    begin
        @(negedge clk);
        if(data_first) begin
            s_wdata=value; s_wstrb=strb; s_wvalid=1;
            do begin @(posedge clk); end while(!s_wready);
            @(negedge clk); s_wvalid=0;
            repeat(3) tick(); @(negedge clk);
        end
        s_awaddr=addr; s_awvalid=1;
        do begin @(posedge clk); end while(!s_awready);
        @(negedge clk); s_awvalid=0;
        if(!data_first) begin
            repeat(2) tick(); @(negedge clk);
            s_wdata=value; s_wstrb=strb; s_wvalid=1;
            do begin @(posedge clk); end while(!s_wready);
            @(negedge clk); s_wvalid=0;
        end
        while(!s_bvalid) tick();
        if(s_bresp !== (fail?2:0)) $fatal(1,"BAR write response addr=%h resp=%d",addr,s_bresp);
        repeat(3) begin tick(); if(!s_bvalid) $fatal(1,"B lost"); end
        @(negedge clk); s_bready=1; tick(); @(negedge clk); s_bready=0;
    end
endtask
task read_reg(input [31:0] addr,value,input bit fail);
    begin
        @(negedge clk); s_araddr=addr; s_arvalid=1;
        do begin @(posedge clk); end while(!s_arready);
        @(negedge clk); s_arvalid=0;
        while(!s_rvalid) tick();
        if(s_rresp !== (fail?2:0) || (!fail && s_rdata !== value))
            $fatal(1,"BAR read addr=%h got=%h expected=%h resp=%d",addr,s_rdata,value,s_rresp);
        repeat(3) begin tick(); if(!s_rvalid) $fatal(1,"R lost"); end
        @(negedge clk); s_rready=1; tick(); @(negedge clk); s_rready=0;
    end
endtask
initial begin
    reset=1; repeat(4) tick(); @(negedge clk); reset=0;
    read_reg(0,32'h46415345,0); read_reg(4,1,0);
    write_reg(12,32'h12345678,15,0,1);
    write_reg(12,32'hdeadbeef,5,0,0);
    read_reg(12,32'h12ad56ef,0);
    write_reg(16,32'h00002a03,15,0,0);
    write_reg(20,32'h89abcdef,15,0,1);
    write_reg(24,32'h01234567,15,0,0);
    write_reg(28,32'h80200000,15,0,0);
    write_reg(32,0,15,0,1);
    write_reg(36,42,15,0,0);
    read_reg(8,1,0);
    if(!cmd_valid || cmd_opcode!=3 || cmd_index!=42 || cmd_data!=64'h0123456789abcdef || cmd_pc!=64'h80200000)
        $fatal(1,"command snapshot");
    write_reg(20,0,15,1,0); // Fields locked while stalled.
    write_reg(36,43,15,1,1); // No overwrite of pending command.
    @(negedge clk); cmd_ready=1; tick(); @(negedge clk); cmd_ready=0;
    if(cmd_valid || !rsp_ready) $fatal(1,"command handshake");
    repeat(7) tick();
    @(negedge clk); rsp_valid=1; rsp_error=1; rsp_data=64'hfedcba9876543210;
    tick(); @(negedge clk); rsp_valid=0;
    read_reg(8,6,0); read_reg(40,42,0);
    read_reg(44,32'h76543210,0); read_reg(48,32'hfedcba98,0);
    write_reg(36,43,15,1,0); // Unread response not overwritten.
    write_reg(56,41,15,1,0);
    write_reg(56,42,15,0,1);
    read_reg(8,0,0);
    read_words=123; write_words=456; memory_errors=7;
    read_reg(64,123,0); read_reg(68,456,0); read_reg(72,7,0);
    read_reg(1,0,1); read_reg(32'h10000,0,1); read_reg(80,0,1);
    write_reg(0,0,15,1,0);
    write_reg(36,99,15,0,0);
    if(!cmd_valid) $fatal(1,"reset test missing pending command");
    @(negedge clk); reset=1; tick(); tick(); @(negedge clk); reset=0;
    if(cmd_valid || rsp_ready || s_bvalid || s_rvalid) $fatal(1,"command survived reset");
    read_reg(8,0,0); read_reg(40,0,0);
    $display("FLOW_PCIE_CONTROL_PASS"); $finish;
end
endmodule

`timescale 1ns/1ps
module memory_tb;
reg  clk = 0;
reg  reset = 0;
reg [3:0] s_awid = 0;
reg [3:0] s_arid = 0;
reg [63:0] s_awaddr = 0;
reg [63:0] s_araddr = 0;
reg [7:0] s_awlen = 0;
reg [7:0] s_arlen = 0;
reg [2:0] s_awsize = 0;
reg [2:0] s_arsize = 0;
reg [1:0] s_awburst = 0;
reg [1:0] s_arburst = 0;
reg  s_awvalid = 0;
reg  s_arvalid = 0;
wire  s_awready;
wire  s_arready;
reg [255:0] s_wdata = 0;
reg [31:0] s_wstrb = 0;
reg  s_wlast = 0;
reg  s_wvalid = 0;
wire  s_wready;
wire [3:0] s_bid;
wire [3:0] s_rid;
wire [1:0] s_bresp;
wire [1:0] s_rresp;
wire  s_bvalid;
wire  s_rvalid;
wire  s_rlast;
reg  s_bready = 0;
reg  s_rready = 0;
wire [255:0] s_rdata;
wire  wb_cyc;
wire  wb_stb;
wire  wb_we;
wire [28:0] wb_adr;
wire [63:0] wb_dat_w;
wire [7:0] wb_sel;
reg [63:0] wb_dat_r = 0;
reg  wb_ack = 0;
reg  wb_err = 0;
wire [31:0] read_words;
wire [31:0] write_words;
wire [31:0] errors;
FlowPcieMemory dut (.*);
always #5 clk = ~clk;
initial begin #2000000; $fatal(1,"timeout"); end
reg [7:0] mem[0:4095];
reg [7:0] expected[0:4095];
integer wait_cycles=0, transactions=0, k;
reg inject_error=0;
always @* begin
    wb_ack = wb_cyc && wait_cycles == 3 && !inject_error;
    wb_err = wb_cyc && wait_cycles == 3 && inject_error;
    for(integer j=0;j<8;j=j+1) wb_dat_r[j*8 +:8] = mem[{wb_adr[8:0],3'b0}+j];
end
always @(posedge clk) begin
    if (!wb_cyc) wait_cycles <= 0;
    else if(wait_cycles < 3) wait_cycles <= wait_cycles+1;
    if (wb_ack || wb_err) begin
        transactions <= transactions+1;
        if (wb_ack && wb_we)
            for(integer j=0;j<8;j=j+1) if(wb_sel[j]) mem[{wb_adr[8:0],3'b0}+j] <= wb_dat_w[j*8 +:8];
    end
end
task tick; begin @(posedge clk); #1; end endtask
task write_burst(input [63:0] a, input integer n, input integer sz,
                 input [31:0] strobes, input bit fail);
    reg [63:0] pos;
    reg [31:0] allowed;
    reg [255:0] payload;
    integer beat,j;
    begin
        @(negedge clk);
        s_awaddr=a; s_awlen=n-1; s_awsize=sz; s_awburst=1; s_awid=9; s_awvalid=1;
        do begin @(posedge clk); end while(!s_awready);
        @(negedge clk); s_awvalid=0;
        pos=a;
        for(beat=0;beat<n;beat=beat+1) begin
            repeat(beat%3) tick();
            allowed=((64'd1 << (1<<sz))-1) << ((pos[4:0]>>sz)<<sz);
            allowed=allowed & (32'hffffffff<<pos[4:0]) & strobes;
            for(j=0;j<32;j=j+1) payload[j*8 +:8]=(beat*37+j+13);
            @(negedge clk); s_wdata=payload; s_wstrb=allowed; s_wvalid=1; s_wlast=(beat==n-1);
            do begin @(posedge clk); end while(!s_wready);
            if(!fail) for(j=0;j<32;j=j+1) if(allowed[j]) expected[(pos[11:5]*32)+j]=payload[j*8 +:8];
            @(negedge clk); s_wvalid=0;
            pos=(pos & ~((64'd1<<sz)-1))+(64'd1<<sz);
        end
        while(!s_bvalid) tick();
        if(s_bresp !== (fail?2:0) || s_bid !== 9) $fatal(1,"write response a=%h resp=%d",a,s_bresp);
        repeat(4) begin tick(); if(!s_bvalid) $fatal(1,"B lost under backpressure"); end
        @(negedge clk); s_bready=1; tick(); @(negedge clk); s_bready=0;
    end
endtask
task read_burst(input [63:0] a,input integer n,input integer sz,input bit fail);
    reg[63:0] pos;
    reg[31:0] allowed;
    reg[255:0] held;
    integer beat,j;
    begin
        @(negedge clk); s_araddr=a; s_arlen=n-1; s_arsize=sz; s_arburst=1; s_arid=6; s_arvalid=1;
        do begin @(posedge clk); end while(!s_arready);
        @(negedge clk); s_arvalid=0;
        pos=a;
        for(beat=0;beat<n;beat=beat+1) begin
            while(!s_rvalid) tick();
            if(s_rresp !== (fail?2:0) || s_rid !== 6 || s_rlast !== (beat==n-1)) $fatal(1,"read response");
            allowed=((64'd1 << (1<<sz))-1) << ((pos[4:0]>>sz)<<sz);
            allowed=allowed & (32'hffffffff<<pos[4:0]);
            if(!fail) for(j=0;j<32;j=j+1) if(allowed[j] && s_rdata[j*8 +:8] !== expected[pos[11:5]*32+j])
                $fatal(1,"read mismatch addr=%h byte=%d got=%h expected=%h",pos,j,s_rdata[j*8 +:8],expected[pos[11:5]*32+j]);
            held=s_rdata;
            repeat(3) begin tick(); if(!s_rvalid || s_rdata !== held) $fatal(1,"R unstable"); end
            @(negedge clk); s_rready=1; tick(); @(negedge clk); s_rready=0;
            pos=(pos & ~((64'd1<<sz)-1))+(64'd1<<sz);
        end
    end
endtask
integer before_count;
initial begin
    reset=1;
    for(k=0;k<4096;k=k+1) begin mem[k]=0; expected[k]=0; end
    repeat(4) tick(); @(negedge clk); reset=0;
    write_burst(64'h80000000,16,5,32'hffffffff,0);
    read_burst(64'h80000000,16,5,0);
    write_burst(64'h80000113,4,5,32'ha55a5aa5,0);
    read_burst(64'h80000113,4,5,0);
    for(k=0;k<=4;k=k+1) begin
        write_burst(64'h80000200,8,k,32'hffffffff,0);
        read_burst(64'h80000200,8,k,0);
    end
    before_count=transactions;
    write_burst(64'h180000000,2,5,32'hffffffff,1);
    read_burst(64'h12001000,2,5,1);
    write_burst(64'h80000fe0,2,5,32'hffffffff,1);
    read_burst(64'h80000003,1,2,1);
    if(transactions!=before_count) $fatal(1,"invalid address reached Wishbone");
    inject_error=1;
    write_burst(64'h80000300,2,5,32'hffffffff,1);
    read_burst(64'h80000300,2,5,1);
    inject_error=0;
    write_burst(64'h80000300,2,5,32'hffffffff,0);
    read_burst(64'h80000300,2,5,0);
    // Concurrent address channels: finish one burst without losing the other.
    fork
        write_burst(64'h80000400,8,5,32'hffffffff,0);
        read_burst(64'h80000000,8,5,0);
    join
    read_burst(64'h80000400,8,5,0);
    // The private test aperture and the largest legal full-width burst.
    write_burst(64'h40000000,128,5,32'hffffffff,0);
    read_burst(64'h40000000,128,5,0);
    for(k=0;k<4096;k=k+1) if(mem[k]!==expected[k]) $fatal(1,"memory corruption %d",k);
    $display("FLOW_PCIE_MEMORY_PASS words_read=%0d words_written=%0d errors=%0d",read_words,write_words,errors);
    $finish;
end
endmodule

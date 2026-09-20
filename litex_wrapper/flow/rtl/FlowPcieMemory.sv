// XDMA AXI-MM -> classic 64-bit Wishbone, one burst outstanding.
// DDR plus a private 4 KiB PCIe test RAM at 0x40000000.
// Full-width INCR bursts, including an unaligned first beat,
// and aligned narrow INCR bursts are supported. No speculative ACKs.
module FlowPcieMemory (
    input wire clk, reset,
    input wire [3:0] s_awid, s_arid,
    input wire [63:0] s_awaddr, s_araddr,
    input wire [7:0] s_awlen, s_arlen,
    input wire [2:0] s_awsize, s_arsize,
    input wire [1:0] s_awburst, s_arburst,
    input wire s_awvalid, s_arvalid,
    output wire s_awready, s_arready,
    input wire [255:0] s_wdata,
    input wire [31:0] s_wstrb,
    input wire s_wlast, s_wvalid,
    output wire s_wready,
    output wire [3:0] s_bid, s_rid,
    output wire [1:0] s_bresp, s_rresp,
    output wire s_bvalid, s_rvalid, s_rlast,
    input wire s_bready, s_rready,
    output wire [255:0] s_rdata,
    output wire wb_cyc, wb_stb, wb_we,
    output wire [28:0] wb_adr,
    output wire [63:0] wb_dat_w,
    output wire [7:0] wb_sel,
    input wire [63:0] wb_dat_r,
    input wire wb_ack, wb_err,
    output reg [31:0] read_words, write_words, errors
);
    localparam IDLE=0, WDATA=1, WORD=2, GAP=3, BRESP=4, RRESP=5;
    reg [2:0] state;
    reg writing, last_was_read;
    reg [63:0] addr;
    reg [7:0] left;
    reg [2:0] size;
    reg [3:0] id;
    reg [1:0] lane;
    reg [255:0] data;
    reg [31:0] mask;
    reg bad, beat_bad;
    wire pick_write = s_awvalid && (!s_arvalid || last_was_read);
    assign s_awready = !reset && state == IDLE && pick_write;
    assign s_arready = !reset && state == IDLE && !pick_write;
    assign s_wready = !reset && state == WDATA;
    assign s_bid = id;
    assign s_rid = id;
    assign s_bvalid = !reset && state == BRESP;
    assign s_rvalid = !reset && state == RRESP;
    assign s_bresp = bad ? 2'b10 : 2'b00;
    assign s_rresp = (bad || beat_bad) ? 2'b10 : 2'b00;
    assign s_rlast = left == 0;
    assign s_rdata = data;
    assign wb_sel = mask[lane*8 +: 8];
    assign wb_cyc = !reset && state == WORD && !bad && |wb_sel;
    assign wb_stb = wb_cyc;
    assign wb_we = writing;
    assign wb_adr = {addr[31:5], lane};
    assign wb_dat_w = data[lane*64 +: 64];

    function automatic invalid_request(input [63:0] a, input [7:0] len,
        input [2:0] sz, input [1:0] burst);
        reg [64:0] end_addr;
        begin
            // Last byte of the aligned final transfer. AXI bursts cannot cross 4 KiB.
            end_addr = {1'b0, (a & ~((64'd1 << sz)-64'd1))} + (({57'b0,len}+1) << sz)-1;
            invalid_request = sz > 5 || burst != 2'b01 ||
                !((a >= 64'h80000000 && end_addr < 65'h100000000) ||
                  (a >= 64'h40000000 && end_addr < 65'h40001000)) ||
                a[63:12] != end_addr[63:12] ||
                (sz < 5 && (a & ((64'd1 << sz)-64'd1)) != 0);
        end
    endfunction
    function automatic [31:0] byte_mask(input [63:0] a, input [2:0] sz);
        reg [63:0] m;
        begin
            m = ((64'd1 << (1 << sz))-1) << ((a[4:0] >> sz) << sz);
            byte_mask = m[31:0] & (32'hffffffff << a[4:0]);
        end
    endfunction
    wire [63:0] next_addr = (addr & ~((64'd1 << size)-64'd1)) + (64'd1 << size);
    wire word_done = !wb_cyc || wb_ack || wb_err;
    always @(posedge clk) begin
        if (reset) begin
            state <= IDLE; writing <= 0; last_was_read <= 1;
            addr <= 0; left <= 0; size <= 0; id <= 0; lane <= 0;
            data <= 0; mask <= 0; bad <= 0; beat_bad <= 0;
            read_words <= 0; write_words <= 0; errors <= 0;
        end else case (state)
            IDLE: if (s_awvalid || s_arvalid) begin
                writing <= pick_write;
                last_was_read <= !pick_write;
                addr <= pick_write ? s_awaddr : s_araddr;
                left <= pick_write ? s_awlen : s_arlen;
                size <= pick_write ? s_awsize : s_arsize;
                id <= pick_write ? s_awid : s_arid;
                bad <= pick_write ? invalid_request(s_awaddr,s_awlen,s_awsize,s_awburst)
                                  : invalid_request(s_araddr,s_arlen,s_arsize,s_arburst);
                beat_bad <= 0; lane <= 0; data <= 0;
                mask <= byte_mask(s_araddr,s_arsize);
                state <= pick_write ? WDATA : WORD;
            end
            WDATA: if (s_wvalid) begin
                data <= s_wdata;
                mask <= s_wstrb & byte_mask(addr,size);
                // Drain a rejected burst so XDMA gets an error response.
                bad <= bad || (s_wlast != (left == 0)) ||
                    |(s_wstrb & ~byte_mask(addr,size));
                lane <= 0;
                state <= WORD;
            end
            WORD: if (word_done) begin
                if (wb_cyc) begin
                    if (wb_err) begin
                        beat_bad <= 1;
                        if (writing) bad <= 1;
                        errors <= errors + 1;
                    end else if (writing) write_words <= write_words + 1;
                    else begin
                        data[lane*64 +: 64] <= wb_dat_r;
                        read_words <= read_words + 1;
                    end
                end
                // Drop CYC between classic transfers, including after ERR.
                state <= GAP;
            end
            GAP: if (lane != 3) begin lane <= lane + 1; state <= WORD; end
                else if (!writing) state <= RRESP;
                else if (left == 0) state <= BRESP;
                else begin left <= left - 1; addr <= next_addr; state <= WDATA; end
            BRESP: if (s_bready) state <= IDLE;
            RRESP: if (s_rready) begin
                if (left == 0) state <= IDLE;
                else begin
                    left <= left - 1; addr <= next_addr; lane <= 0;
                    data <= 0; mask <= byte_mask(next_addr,size); beat_bad <= 0;
                    state <= WORD;
                end
            end
            default: state <= IDLE;
        endcase
    end
endmodule

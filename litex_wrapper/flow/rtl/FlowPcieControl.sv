// AXI-Lite BAR window; all state is in the SoC clock domain.
// Command fields are frozen by SUBMIT. A response must be ACKed before reuse.
module FlowPcieControl (
    input wire clk, reset,
    input wire [31:0] s_awaddr, s_araddr, s_wdata,
    input wire [3:0] s_wstrb,
    input wire s_awvalid, s_wvalid, s_arvalid, s_bready, s_rready,
    output wire s_awready, s_wready, s_arready,
    output reg s_bvalid, s_rvalid,
    output reg [1:0] s_bresp, s_rresp,
    output reg [31:0] s_rdata,
    output wire cmd_valid,
    input wire cmd_ready,
    output reg [7:0] cmd_opcode,
    output reg [5:0] cmd_index,
    output reg [63:0] cmd_data, cmd_pc,
    input wire rsp_valid, rsp_error,
    input wire [63:0] rsp_data,
    output wire rsp_ready,
    input wire [31:0] read_words, write_words, memory_errors
);
    reg aw_full, w_full;
    reg [31:0] aw, wd;
    reg [3:0] ws;
    reg [31:0] args [0:4];
    reg [31:0] submitted, completed, scratch;
    reg [63:0] result;
    reg pending, waiting, done, error;
    wire busy = pending || waiting;
    assign s_awready = !reset && !aw_full && !s_bvalid;
    assign s_wready = !reset && !w_full && !s_bvalid;
    assign s_arready = !reset && !s_rvalid;
    assign cmd_valid = pending;
    assign rsp_ready = waiting;
    integer i, b;
    always @(posedge clk) begin
        if (reset) begin
            aw_full <= 0; w_full <= 0; aw <= 0; wd <= 0; ws <= 0;
            s_bvalid <= 0; s_rvalid <= 0; s_bresp <= 0; s_rresp <= 0; s_rdata <= 0;
            submitted <= 0; completed <= 0; scratch <= 0; result <= 0;
            pending <= 0; waiting <= 0; done <= 0; error <= 0;
            cmd_opcode <= 0; cmd_index <= 0; cmd_data <= 0; cmd_pc <= 0;
            for (i=0; i<5; i=i+1) args[i] <= 0;
        end else begin
            if (s_awvalid && s_awready) begin aw <= s_awaddr; aw_full <= 1; end
            if (s_wvalid && s_wready) begin wd <= s_wdata; ws <= s_wstrb; w_full <= 1; end
            if (s_bvalid && s_bready) s_bvalid <= 0;
            if (s_rvalid && s_rready) s_rvalid <= 0;
            if (pending && cmd_ready) begin pending <= 0; waiting <= 1; end
            if (waiting && rsp_valid) begin
                waiting <= 0; done <= 1; error <= rsp_error;
                result <= rsp_data; completed <= submitted;
            end
            if (aw_full && w_full && !s_bvalid) begin
                aw_full <= 0; w_full <= 0; s_bvalid <= 1; s_bresp <= 0;
                if (aw[1:0] != 0 || aw[31:16] != 0) s_bresp <= 2'b10;
                else case (aw[15:0])
                    16'h000c: for (b=0;b<4;b=b+1) if (ws[b]) scratch[b*8 +:8] <= wd[b*8 +:8];
                    16'h0010,16'h0014,16'h0018,16'h001c,16'h0020:
                        if (busy || done) s_bresp <= 2'b10;
                        else for (b=0;b<4;b=b+1) if(ws[b]) args[(aw-16)/4][b*8 +:8] <= wd[b*8 +:8];
                    16'h0024: if (busy || done || ws != 4'hf) s_bresp <= 2'b10;
                        else begin
                            submitted <= wd; pending <= 1;
                            cmd_opcode <= args[0][7:0]; cmd_index <= args[0][13:8];
                            cmd_data <= {args[2],args[1]}; cmd_pc <= {args[4],args[3]};
                        end
                    16'h0038: if (busy || ws != 4'hf || wd != completed) s_bresp <= 2'b10;
                        else begin done <= 0; error <= 0; end
                    default: s_bresp <= 2'b10;
                endcase
            end
            if (s_arvalid && s_arready) begin
                s_rvalid <= 1; s_rresp <= 0; s_rdata <= 0;
                if (s_araddr[1:0] != 0 || s_araddr[31:16] != 0) s_rresp <= 2'b10;
                else case (s_araddr[15:0])
                    16'h0000: s_rdata <= 32'h46415345; // FASE
                    16'h0004: s_rdata <= 1; // ABI
                    16'h0008: s_rdata <= {29'b0,error,done,busy};
                    16'h000c: s_rdata <= scratch;
                    16'h0010,16'h0014,16'h0018,16'h001c,16'h0020: s_rdata <= args[(s_araddr-16)/4];
                    16'h0024: s_rdata <= submitted;
                    16'h0028: s_rdata <= completed;
                    16'h002c: s_rdata <= result[31:0];
                    16'h0030: s_rdata <= result[63:32];
                    16'h0040: s_rdata <= read_words;
                    16'h0044: s_rdata <= write_words;
                    16'h0048: s_rdata <= memory_errors;
                    default: s_rresp <= 2'b10;
                endcase
            end
        end
    end
endmodule

// USER2 transport. One transaction outstanding; ACK means response stored,
// not instruction retired (EXEC completion is polled with STATUS).
// Frame, LSB first: opcode[7:0], index[13:8], data[77:14], pc[141:78],
// tag[157:142], reserved[175:158]=0, magic[191:176]=16'hfa5e.
module FlowFaseJtagTransport (
    input wire sys_clk, input wire reset,
    input wire tck, input wire sel, input wire capture, input wire shift,
    input wire update, input wire tap_reset, input wire tdi, output wire tdo,
    output wire cmd_valid, input wire cmd_ready,
    output wire [7:0] cmd_opcode, output wire [5:0] cmd_index,
    output wire [63:0] cmd_data, output wire [63:0] cmd_pc,
    input wire rsp_valid, output wire rsp_ready,
    input wire rsp_error, input wire [63:0] rsp_data,
    output wire [7:0] debug_flags,
    output reg [15:0] debug_received, debug_dispatched, debug_responded,
    output wire [15:0] debug_tag
);
    // Board/CPU reset clears BOTH sides; TAP reset only discards a partial scan.
    // Async assertion covers stopped TCK, synchronized release in each domain.
    (* ASYNC_REG = "TRUE" *) reg [1:0] t_reset = 2'b11, s_reset = 2'b11;
    always @(posedge tck or posedge reset)
        if (reset) t_reset <= 2'b11; else t_reset <= {t_reset[0], 1'b0};
    always @(posedge sys_clk or posedge reset)
        if (reset) s_reset <= 2'b11; else s_reset <= {s_reset[0], 1'b0};
    wire trst = t_reset[1], srst = s_reset[1];
    (* KEEP = "TRUE" *) reg [157:0] cmd_hold;
    (* KEEP = "TRUE" *) reg [80:0] rsp_hold;
    (* KEEP = "TRUE" *) reg [80:0] response;
    reg response_latched;
    reg [191:0] scan;
    reg [8:0] shift_count;
    reg req, ack, has_command, response_read;
    reg [4:0] jtag_seen;
    (* ASYNC_REG = "TRUE" *) reg req_meta, req_sync, ack_meta, ack_sync;
    (* ASYNC_REG = "TRUE" *) reg [4:0] seen_meta, seen_sync;
    wire busy = req != ack_sync;
    wire complete = has_command && !busy;
    wire response_valid = complete && response_latched;
    // Read frame: data[63:0], error[64], valid[65], busy[66], rejected[67],
    // tag[83:68], version[175:168]=1, magic[191:176]=fa5e.
    wire [191:0] read_frame = {16'hfa5e, 8'h01, 84'b0,
        response[80:65], jtag_seen[4], busy, response_valid,
        response_valid && response[64], response_valid ? response[63:0] : 64'b0};
    assign tdo = scan[0];
    always @(posedge tck or posedge trst) begin
        if (trst) begin
            scan <= 0; shift_count <= 0; cmd_hold <= 0; req <= 0;
            has_command <= 0; response_read <= 0; jtag_seen <= 0;
            response <= 0; response_latched <= 0;
            ack_meta <= 0; ack_sync <= 0;
        end else begin
            ack_meta <= ack; ack_sync <= ack_meta;
            // A single controlled capture of the stable bundled response.
            // The shift register never directly samples an asynchronous bus.
            if (complete && !response_latched) begin
                response <= rsp_hold;
                response_latched <= 1;
            end
            if (sel) jtag_seen[0] <= 1;
            if (tap_reset) begin scan <= 0; shift_count <= 0; end
            else if (sel) begin
                if (capture) begin
                    scan <= read_frame; shift_count <= 0; jtag_seen[1] <= 1;
                    if (response_valid) response_read <= 1;
                end else if (shift) begin
                    scan <= {tdi, scan[191:1]}; jtag_seen[2] <= 1;
                    if (shift_count != 511) shift_count <= shift_count + 1'b1;
                end
                if (update) begin
                    jtag_seen[3] <= 1;
                    if (scan[191:176] == 16'hfa5e) begin
                        // Up to 16 other devices may shift one BYPASS bit each.
                        if (shift_count >= 192 && shift_count <= 208 && scan[175:158] == 0 &&
                            !busy && (!has_command || response_read)) begin
                            cmd_hold <= scan[157:0]; req <= ~req;
                            has_command <= 1; response_read <= 0;
                            response_latched <= 0;
                        end else jtag_seen[4] <= 1;
                    end
                    shift_count <= 0;
                end
            end
        end
    end
    localparam IDLE=0, SEND=1, WAIT_RESPONSE=2;
    reg [1:0] state;
    reg [157:0] command;
    reg req_seen;
    assign cmd_valid = state == SEND;
    assign {debug_tag, cmd_pc, cmd_data, cmd_index, cmd_opcode} = command;
    assign rsp_ready = state == WAIT_RESPONSE;
    assign debug_flags = {seen_sync, ack, req_sync, req_sync != ack};
    always @(posedge sys_clk or posedge srst) begin
        if (srst) begin
            req_meta <= 0; req_sync <= 0; seen_meta <= 0; seen_sync <= 0;
            state <= IDLE; command <= 0; req_seen <= 0; ack <= 0; rsp_hold <= 0;
            debug_received <= 0; debug_dispatched <= 0; debug_responded <= 0;
        end else begin
            req_meta <= req; req_sync <= req_meta;
            seen_meta <= jtag_seen; seen_sync <= seen_meta;
            case (state)
                IDLE: if (req_sync != ack) begin
                    command <= cmd_hold; req_seen <= req_sync; state <= SEND;
                    debug_received <= debug_received + 1'b1;
                end
                SEND: if (cmd_ready) begin
                    state <= WAIT_RESPONSE;
                    debug_dispatched <= debug_dispatched + 1'b1;
                end
                WAIT_RESPONSE: if (rsp_valid) begin
                    rsp_hold <= {debug_tag, rsp_error, rsp_data}; ack <= req_seen;
                    state <= IDLE; debug_responded <= debug_responded + 1'b1;
                end
                default: state <= IDLE;
            endcase
        end
    end
endmodule

module FlowFaseJtag (
    input wire sys_clk, reset,
    output wire cmd_valid, input wire cmd_ready,
    output wire [7:0] cmd_opcode, output wire [5:0] cmd_index,
    output wire [63:0] cmd_data, cmd_pc,
    input wire rsp_valid, output wire rsp_ready,
    input wire rsp_error, input wire [63:0] rsp_data,
    output wire [7:0] debug_flags,
    output wire [15:0] debug_received, debug_dispatched, debug_responded, debug_tag
);
    wire tck_raw, tck, sel, capture, shift, update, tap_reset, tdi, tdo;
    // USER1 remains available for Vivado's debug hub / ILA.
    BSCANE2 #(.JTAG_CHAIN(2)) bscan (
        .TCK(tck_raw), .SEL(sel), .CAPTURE(capture), .SHIFT(shift),
        .UPDATE(update), .RESET(tap_reset), .TDI(tdi), .TDO(tdo),
        .DRCK(), .RUNTEST(), .TMS());
    BUFG tck_buffer (.I(tck_raw), .O(tck));
    FlowFaseJtagTransport transport (.*);
endmodule

`default_nettype none

// Standalone RISC-V CLINT for the Flow LiteX Linux platform.
// LiteX owns address decoding and interconnect; this block owns all CLINT
// register, timebase, software-interrupt and timer-interrupt semantics.
module FlowClint #(
    parameter integer NUM_HARTS       = 4,
    parameter integer SYS_CLK_FREQ    = 50000000,
    parameter integer TIMEBASE_FREQ   = 1000000,
    parameter integer REGION_BYTES    = 65536,
    parameter integer MSIP_OFFSET     = 32'h0000,
    parameter integer MTIMECMP_OFFSET = 32'h4000,
    parameter integer MTIME_OFFSET    = 32'hbff8
) (
    input  wire                       clk,
    input  wire                       rst,

    input  wire [31:0]                wb_adr,
    input  wire [63:0]                wb_dat_w,
    output wire [63:0]                wb_dat_r,
    input  wire [7:0]                 wb_sel,
    input  wire                       wb_cyc,
    input  wire                       wb_stb,
    output wire                       wb_ack,
    input  wire                       wb_we,
    output wire                       wb_err,

    output wire [NUM_HARTS-1:0]       msip,
    output wire [NUM_HARTS-1:0]       mtip,
    output wire [63:0]                mtime,

    output wire [NUM_HARTS-1:0]       debug_msip,
    output wire [NUM_HARTS*64-1:0]    debug_mtimecmp
);
    localparam integer TIMEBASE_DIVISOR = SYS_CLK_FREQ / TIMEBASE_FREQ;
    localparam integer DIVIDER_BITS =
        (TIMEBASE_DIVISOR < 2) ? 1 : $clog2(TIMEBASE_DIVISOR);
    localparam integer REGION_WORDS = REGION_BYTES / 8;
    localparam [DIVIDER_BITS-1:0] DIVIDER_LIMIT =
        DIVIDER_BITS'(TIMEBASE_DIVISOR - 1);

    reg [NUM_HARTS-1:0] msip_reg;
    reg [63:0] mtime_reg;
    reg [63:0] mtimecmp_reg [0:NUM_HARTS-1];
    reg [DIVIDER_BITS-1:0] divider;

    reg responding;
    reg [63:0] read_data;
    reg [63:0] read_mux;

    wire request = wb_cyc && wb_stb && !responding;
    // The decoded LiteX slave still sees the global 64-bit word address.
    // The CLINT region is power-of-two sized and naturally aligned.
    wire [31:0] wb_local_adr = wb_adr & (REGION_WORDS - 1);
    wire tick = (TIMEBASE_DIVISOR == 1) ? 1'b1 :
        (divider == DIVIDER_LIMIT);

    assign wb_ack = responding && wb_cyc && wb_stb;
    assign wb_err = 1'b0;
    assign wb_dat_r = read_data;
    assign msip = msip_reg;
    assign mtime = mtime_reg;
    assign debug_msip = msip_reg;

    genvar output_hart;
    generate
        for (output_hart = 0; output_hart < NUM_HARTS; output_hart = output_hart + 1) begin : gen_outputs
            assign mtip[output_hart] = (mtime_reg >= mtimecmp_reg[output_hart]);
            assign debug_mtimecmp[64*output_hart +: 64] = mtimecmp_reg[output_hart];
        end
    endgenerate

    integer read_hart;
    always @* begin
        read_mux = 64'b0;
        for (read_hart = 0; read_hart < NUM_HARTS; read_hart = read_hart + 1) begin
            if (wb_local_adr == ((MSIP_OFFSET + 4*read_hart) / 8))
                read_mux[32*(read_hart % 2)] = msip_reg[read_hart];
            if (wb_local_adr == ((MTIMECMP_OFFSET + 8*read_hart) / 8))
                read_mux = mtimecmp_reg[read_hart];
        end
        if (wb_local_adr == (MTIME_OFFSET / 8))
            read_mux = mtime_reg;
    end

    integer reset_hart;
    integer seq_hart;
    integer seq_byte;
    always @(posedge clk) begin
        if (rst) begin
            responding <= 1'b0;
            read_data <= 64'b0;
            msip_reg <= {NUM_HARTS{1'b0}};
            mtime_reg <= 64'b0;
            divider <= {DIVIDER_BITS{1'b0}};
            for (reset_hart = 0; reset_hart < NUM_HARTS; reset_hart = reset_hart + 1)
                mtimecmp_reg[reset_hart] <= 64'hffff_ffff_ffff_ffff;
        end else begin
            if (TIMEBASE_DIVISOR == 1) begin
                divider <= {DIVIDER_BITS{1'b0}};
            end else if (tick) begin
                divider <= {DIVIDER_BITS{1'b0}};
            end else begin
                divider <= divider + 1'b1;
            end

            if (request) begin
                responding <= 1'b1;
                read_data <= read_mux;
            end else if (!wb_cyc || !wb_stb) begin
                responding <= 1'b0;
            end

            if (request && wb_we) begin
                for (seq_hart = 0; seq_hart < NUM_HARTS; seq_hart = seq_hart + 1) begin
                    // Two 32-bit MSIP registers share one 64-bit Wishbone word.
                    if ((wb_local_adr == ((MSIP_OFFSET + 4*seq_hart) / 8)) &&
                        wb_sel[4*(seq_hart % 2)])
                        msip_reg[seq_hart] <= wb_dat_w[32*(seq_hart % 2)];

                    if (wb_local_adr == ((MTIMECMP_OFFSET + 8*seq_hart) / 8)) begin
                        for (seq_byte = 0; seq_byte < 8; seq_byte = seq_byte + 1) begin
                            if (wb_sel[seq_byte])
                                mtimecmp_reg[seq_hart][8*seq_byte +: 8] <=
                                    wb_dat_w[8*seq_byte +: 8];
                        end
                    end
                end
            end

            // An MMIO write has precedence over the free-running tick.
            if (request && wb_we && (wb_local_adr == (MTIME_OFFSET / 8))) begin
                for (seq_byte = 0; seq_byte < 8; seq_byte = seq_byte + 1) begin
                    if (wb_sel[seq_byte])
                        mtime_reg[8*seq_byte +: 8] <= wb_dat_w[8*seq_byte +: 8];
                end
            end else if (tick) begin
                mtime_reg <= mtime_reg + 1'b1;
            end
        end
    end
endmodule

`default_nettype wire

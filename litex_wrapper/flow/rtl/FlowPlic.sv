`default_nettype none

// Standards-shaped RISC-V PLIC used by the Flow LiteX platform.
//
// The module is deliberately plain SystemVerilog: LiteX only instantiates the
// block and connects its Wishbone slave and interrupt ports.  All register,
// priority, gateway, claim and completion behavior lives here so it can be
// compiled and tested without Migen elaboration.
module FlowPlic #(
    parameter integer NUM_HARTS   = 4,
    parameter integer NUM_SOURCES = 31
) (
    input  wire                         clk,
    input  wire                         rst,

    input  wire [31:0]                  wb_adr,
    input  wire [63:0]                  wb_dat_w,
    output wire [63:0]                  wb_dat_r,
    input  wire [7:0]                   wb_sel,
    input  wire                         wb_cyc,
    input  wire                         wb_stb,
    output wire                         wb_ack,
    input  wire                         wb_we,
    output wire                         wb_err,

    input  wire [NUM_SOURCES-1:0]       sources,
    output wire [NUM_HARTS-1:0]         meip,
    output wire [NUM_HARTS-1:0]         seip,

    // Observation-only state exported for reusable simulation monitors.
    output wire [NUM_SOURCES:0]         debug_pending,
    output wire [2*NUM_HARTS*32-1:0]    debug_claims,
    output wire [NUM_SOURCES*3-1:0]     debug_priorities,
    output wire [2*NUM_HARTS*32-1:0]    debug_enables,
    output wire [2*NUM_HARTS*3-1:0]     debug_thresholds
);
    localparam integer NUM_CONTEXTS = 2 * NUM_HARTS;
    localparam integer SOURCE_BITS = (NUM_SOURCES < 2) ? 1 : $clog2(NUM_SOURCES + 1);

    localparam [31:0] PENDING_WORD = 32'h0000_0200; // 0x1000 / 8.

    reg [2:0] source_priority [1:NUM_SOURCES];
    reg       pending [1:NUM_SOURCES];
    reg       in_flight [1:NUM_SOURCES];
    reg [31:0] enables [0:NUM_CONTEXTS-1];
    reg [2:0] threshold [0:NUM_CONTEXTS-1];

    reg [SOURCE_BITS-1:0] selected [0:NUM_CONTEXTS-1];
    reg [2:0] selected_priority [0:NUM_CONTEXTS-1];

    reg responding;
    reg [63:0] read_data;
    wire request = wb_cyc && wb_stb && !responding;
    // LiteX presents the global Wishbone word address to a decoded slave.
    // The PLIC occupies a naturally aligned 64 MiB byte region, so its local
    // 64-bit-word offset is the low 23 address bits.
    wire [31:0] wb_local_adr = wb_adr & 32'h007f_ffff;

    assign wb_ack = responding && wb_cyc && wb_stb;
    assign wb_err = 1'b0;
    assign wb_dat_r = read_data;

    integer select_context;
    integer select_source;
    integer read_context;
    integer read_source;
    reg [63:0] read_mux;

    // Lowest source ID wins when priorities tie.  Iterating in ascending ID
    // order and replacing only on a strictly larger priority implements that
    // rule without relying on implicit expression widths.
    always @* begin
        for (select_context = 0; select_context < NUM_CONTEXTS; select_context = select_context + 1) begin
            selected[select_context] = {SOURCE_BITS{1'b0}};
            selected_priority[select_context] = 3'b000;
            for (select_source = 1; select_source <= NUM_SOURCES; select_source = select_source + 1) begin
                if (pending[select_source] &&
                    enables[select_context][select_source] &&
                    (source_priority[select_source] > threshold[select_context]) &&
                    (source_priority[select_source] > selected_priority[select_context])) begin
                    selected[select_context] = select_source[SOURCE_BITS-1:0];
                    selected_priority[select_context] = source_priority[select_source];
                end
            end
        end
    end

    genvar hart_gen;
    generate
        for (hart_gen = 0; hart_gen < NUM_HARTS; hart_gen = hart_gen + 1) begin : gen_hart_irqs
            assign meip[hart_gen] = (selected[2*hart_gen] != {SOURCE_BITS{1'b0}});
            assign seip[hart_gen] = (selected[2*hart_gen + 1] != {SOURCE_BITS{1'b0}});
        end
    endgenerate

    // A 64-bit Wishbone word contains two adjacent 32-bit PLIC registers.
    // Reads are latched when a new classic transaction is accepted.
    always @* begin
        read_mux = 64'b0;
        for (read_source = 1; read_source <= NUM_SOURCES; read_source = read_source + 1) begin
            if (wb_local_adr == ((4 * read_source) / 8)) begin
                if ((read_source % 2) == 0)
                    read_mux[2:0] = source_priority[read_source];
                else
                    read_mux[34:32] = source_priority[read_source];
            end
        end
        if (wb_local_adr == PENDING_WORD) begin
            read_mux[0] = 1'b0;
            for (read_source = 1; read_source <= NUM_SOURCES; read_source = read_source + 1)
                read_mux[read_source] = pending[read_source];
        end
        for (read_context = 0; read_context < NUM_CONTEXTS; read_context = read_context + 1) begin
            if (wb_local_adr == ((32'h0000_2000 + 32'h0000_0080 * read_context) / 8))
                read_mux[31:0] = enables[read_context];
            if (wb_local_adr == ((32'h0020_0000 + 32'h0000_1000 * read_context) / 8)) begin
                read_mux[2:0] = threshold[read_context];
                read_mux[32 +: SOURCE_BITS] = selected[read_context];
            end
        end
    end

    integer reset_index;
    integer reset_context;
    integer seq_source;
    integer seq_context;
    integer seq_byte;
    always @(posedge clk) begin
        if (rst) begin
            responding <= 1'b0;
            read_data <= 64'b0;
            for (reset_index = 1; reset_index <= NUM_SOURCES; reset_index = reset_index + 1) begin
                source_priority[reset_index] <= 3'b000;
                pending[reset_index] <= 1'b0;
                in_flight[reset_index] <= 1'b0;
            end
            for (reset_context = 0; reset_context < NUM_CONTEXTS; reset_context = reset_context + 1) begin
                enables[reset_context] <= 32'b0;
                threshold[reset_context] <= 3'b000;
            end
        end else begin
            if (request) begin
                responding <= 1'b1;
                read_data <= read_mux;
            end else if (!wb_cyc || !wb_stb) begin
                responding <= 1'b0;
            end

            // Level gateway.  Once accepted, a source remains in flight until
            // completion; claim clears pending but cannot immediately re-latch
            // a still-high device before software completes it.
            for (seq_source = 1; seq_source <= NUM_SOURCES; seq_source = seq_source + 1) begin
                if (sources[seq_source-1] && !in_flight[seq_source] && !pending[seq_source]) begin
                    pending[seq_source] <= 1'b1;
                    in_flight[seq_source] <= 1'b1;
                end
            end

            if (request && wb_we) begin
                // Priority registers.
                for (seq_source = 1; seq_source <= NUM_SOURCES; seq_source = seq_source + 1) begin
                    if (wb_local_adr == ((4 * seq_source) / 8)) begin
                        if (((seq_source % 2) == 0) && wb_sel[0])
                            source_priority[seq_source] <= wb_dat_w[2:0];
                        if (((seq_source % 2) == 1) && wb_sel[4])
                            source_priority[seq_source] <= wb_dat_w[34:32];
                    end
                end

                for (seq_context = 0; seq_context < NUM_CONTEXTS; seq_context = seq_context + 1) begin
                    // Context enable words.  Source zero is hard-wired off.
                    if (wb_local_adr == ((32'h0000_2000 + 32'h0000_0080 * seq_context) / 8)) begin
                        for (seq_byte = 0; seq_byte < 4; seq_byte = seq_byte + 1) begin
                            if (wb_sel[seq_byte])
                                enables[seq_context][8*seq_byte +: 8] <=
                                    wb_dat_w[8*seq_byte +: 8];
                        end
                        enables[seq_context][0] <= 1'b0;
                    end

                    // Threshold is the low register in the context word.
                    if ((wb_local_adr == ((32'h0020_0000 + 32'h0000_1000 * seq_context) / 8)) && wb_sel[0])
                        threshold[seq_context] <= wb_dat_w[2:0];

                    // Complete is the high register.  Match statically rather
                    // than dynamically indexing an out-of-range source ID.
                    if ((wb_local_adr == ((32'h0020_0000 + 32'h0000_1000 * seq_context) / 8)) && wb_sel[4]) begin
                        for (seq_source = 1; seq_source <= NUM_SOURCES; seq_source = seq_source + 1) begin
                            if ((wb_dat_w[32 +: SOURCE_BITS] == seq_source[SOURCE_BITS-1:0]) &&
                                enables[seq_context][seq_source])
                                in_flight[seq_source] <= 1'b0;
                        end
                    end
                end
            end

            // Claim is the high 32-bit read in each context word.  It clears
            // pending but leaves the level gateway in flight until complete.
            if (request && !wb_we) begin
                for (seq_context = 0; seq_context < NUM_CONTEXTS; seq_context = seq_context + 1) begin
                    if ((wb_local_adr == ((32'h0020_0000 + 32'h0000_1000 * seq_context) / 8)) &&
                        wb_sel[4]) begin
                        for (seq_source = 1; seq_source <= NUM_SOURCES; seq_source = seq_source + 1) begin
                            if (selected[seq_context] == seq_source[SOURCE_BITS-1:0])
                                pending[seq_source] <= 1'b0;
                        end
                    end
                end
            end
        end
    end

    assign debug_pending[0] = 1'b0;
    genvar source_gen;
    generate
        for (source_gen = 1; source_gen <= NUM_SOURCES; source_gen = source_gen + 1) begin : gen_debug_sources
            assign debug_pending[source_gen] = pending[source_gen];
            assign debug_priorities[(source_gen-1)*3 +: 3] = source_priority[source_gen];
        end
    endgenerate

    genvar context_gen;
    generate
        for (context_gen = 0; context_gen < NUM_CONTEXTS; context_gen = context_gen + 1) begin : gen_debug_contexts
            assign debug_claims[context_gen*32 +: 32] =
                {{(32-SOURCE_BITS){1'b0}}, selected[context_gen]};
            assign debug_enables[context_gen*32 +: 32] = enables[context_gen];
            assign debug_thresholds[context_gen*3 +: 3] = threshold[context_gen];
        end
    endgenerate
endmodule

`default_nettype wire

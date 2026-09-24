`timescale 1ns/1ps

// Safe AXI4 sink for the link-only image.  It drains writes and returns DECERR;
// reads return the requested number of zero-data DECERR beats.  This keeps an
// accidental host access from hanging the XDMA master while r1 has no memory.
module PcieAxiDecerr #(
    parameter integer ID_WIDTH = 4,
    parameter integer DATA_WIDTH = 256
) (
    input  wire                  clk,
    input  wire                  reset_n,

    input  wire [ID_WIDTH-1:0]   s_awid,
    input  wire [7:0]            s_awlen,
    input  wire                  s_awvalid,
    output wire                  s_awready,
    input  wire [DATA_WIDTH-1:0] s_wdata,
    input  wire [DATA_WIDTH/8-1:0] s_wstrb,
    input  wire                  s_wlast,
    input  wire                  s_wvalid,
    output wire                  s_wready,
    output reg  [ID_WIDTH-1:0]   s_bid,
    output wire [1:0]            s_bresp,
    output reg                   s_bvalid,
    input  wire                  s_bready,

    input  wire [ID_WIDTH-1:0]   s_arid,
    input  wire [7:0]            s_arlen,
    input  wire                  s_arvalid,
    output wire                  s_arready,
    output reg  [ID_WIDTH-1:0]   s_rid,
    output wire [DATA_WIDTH-1:0] s_rdata,
    output wire [1:0]            s_rresp,
    output wire                  s_rlast,
    output wire                  s_rvalid,
    input  wire                  s_rready
);
    localparam [1:0] AXI_DECERR = 2'b11;

    reg write_active;
    reg read_active;
    reg [7:0] read_left;

    assign s_awready = reset_n && !write_active && !s_bvalid;
    assign s_wready  = reset_n && write_active && !s_bvalid;
    assign s_bresp   = AXI_DECERR;

    assign s_arready = reset_n && !read_active;
    assign s_rdata   = {DATA_WIDTH{1'b0}};
    assign s_rresp   = AXI_DECERR;
    assign s_rvalid  = reset_n && read_active;
    assign s_rlast   = read_left == 0;

    // Payload is intentionally ignored; naming it in the port list preserves
    // the complete AXI contract and avoids unsafe constant READY shortcuts.
    wire unused_write_payload = ^{s_wdata, s_wstrb};

    always @(posedge clk or negedge reset_n) begin
        if (!reset_n) begin
            write_active <= 1'b0;
            s_bid <= {ID_WIDTH{1'b0}};
            s_bvalid <= 1'b0;
            read_active <= 1'b0;
            read_left <= 8'b0;
            s_rid <= {ID_WIDTH{1'b0}};
        end else begin
            if (s_bvalid && s_bready)
                s_bvalid <= 1'b0;

            if (s_awvalid && s_awready) begin
                write_active <= 1'b1;
                s_bid <= s_awid;
            end

            if (s_wvalid && s_wready && s_wlast) begin
                write_active <= 1'b0;
                s_bvalid <= 1'b1;
            end

            if (s_arvalid && s_arready) begin
                read_active <= 1'b1;
                read_left <= s_arlen;
                s_rid <= s_arid;
            end else if (s_rvalid && s_rready) begin
                if (read_left == 0)
                    read_active <= 1'b0;
                else
                    read_left <= read_left - 1'b1;
            end
        end
    end
endmodule


// AXI4-Lite companion.  AW and W are accepted independently, as required by
// AXI, and combined into a single DECERR response.
module PcieAxilDecerr (
    input  wire        clk,
    input  wire        reset_n,
    input  wire [31:0] s_awaddr,
    input  wire [2:0]  s_awprot,
    input  wire        s_awvalid,
    output wire        s_awready,
    input  wire [31:0] s_wdata,
    input  wire [3:0]  s_wstrb,
    input  wire        s_wvalid,
    output wire        s_wready,
    output wire [1:0]  s_bresp,
    output reg         s_bvalid,
    input  wire        s_bready,
    input  wire [31:0] s_araddr,
    input  wire [2:0]  s_arprot,
    input  wire        s_arvalid,
    output wire        s_arready,
    output wire [31:0] s_rdata,
    output wire [1:0]  s_rresp,
    output reg         s_rvalid,
    input  wire        s_rready
);
    localparam [1:0] AXI_DECERR = 2'b11;
    reg aw_seen;
    reg w_seen;

    assign s_awready = reset_n && !aw_seen && !s_bvalid;
    assign s_wready  = reset_n && !w_seen && !s_bvalid;
    assign s_bresp   = AXI_DECERR;
    assign s_arready = reset_n && !s_rvalid;
    assign s_rdata   = 32'b0;
    assign s_rresp   = AXI_DECERR;

    wire unused_payload = ^{s_awaddr, s_awprot, s_wdata, s_wstrb,
                            s_araddr, s_arprot};

    always @(posedge clk or negedge reset_n) begin
        if (!reset_n) begin
            aw_seen <= 1'b0;
            w_seen <= 1'b0;
            s_bvalid <= 1'b0;
            s_rvalid <= 1'b0;
        end else begin
            if (s_awvalid && s_awready)
                aw_seen <= 1'b1;
            if (s_wvalid && s_wready)
                w_seen <= 1'b1;

            if (aw_seen && w_seen && !s_bvalid) begin
                aw_seen <= 1'b0;
                w_seen <= 1'b0;
                s_bvalid <= 1'b1;
            end else if (s_bvalid && s_bready) begin
                s_bvalid <= 1'b0;
            end

            if (s_arvalid && s_arready)
                s_rvalid <= 1'b1;
            else if (s_rvalid && s_rready)
                s_rvalid <= 1'b0;
        end
    end
endmodule

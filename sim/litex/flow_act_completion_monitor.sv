// SPDX-License-Identifier: Apache-2.0
// Passive ACT completion observer for the existing Flow Linux SoC.
// Runtime controls let one compiled SoC run ACT ELFs with different tohost symbols.
module FlowActCompletionMonitor #(
    parameter integer NUM_HARTS = 4
) (
    input  wire                   clock,
    input  wire                   reset,
    input  wire                   retire_valid,
    input  wire                   retire_mem_en,
    input  wire                   retire_mem_is_write,
    input  wire [63:0]            retire_mem_addr,
    input  wire [63:0]            retire_mem_aligned_addr,
    input  wire [63:0]            retire_mem_wdata,
    input  wire [7:0]             retire_mem_wmask,
    input  wire [NUM_HARTS-1:0]   hart_fatal
);
    reg [63:0] tohost_address;
    reg [63:0] max_cycles;
    reg [63:0] cycle_count;
    reg        configuration_valid;

    wire [2:0]  tohost_lane = tohost_address[2:0];
    wire [7:0]  tohost_mask = 8'h0f << tohost_lane;
    wire [31:0] exit_code = retire_mem_wdata >> (tohost_lane * 8);
    wire tohost_store = retire_valid && retire_mem_en && retire_mem_is_write &&
        (retire_mem_aligned_addr == {tohost_address[63:3], 3'b000}) &&
        ((retire_mem_wmask & tohost_mask) == tohost_mask);

    initial begin
        configuration_valid = 1'b1;
        if (!$value$plusargs("act_tohost=%h", tohost_address)) begin
            $display("[ACT4-SOC-INFRA] missing +act_tohost=<hex address>");
            configuration_valid = 1'b0;
        end
        if (!$value$plusargs("act_max_cycles=%d", max_cycles)) begin
            $display("[ACT4-SOC-INFRA] missing +act_max_cycles=<decimal cycles>");
            configuration_valid = 1'b0;
        end
    end

    always @(posedge clock) begin
        if (reset) begin
            cycle_count <= 64'd0;
        end else begin
            cycle_count <= cycle_count + 64'd1;
            if (!configuration_valid) begin
                $finish;
            end
            if (|hart_fatal) begin
                $display("[ACT4-SOC-FATAL] cycle=%0d hart_mask=0x%0h",
                    cycle_count, hart_fatal);
                $finish;
            end
            if (tohost_store) begin
                if (exit_code == 32'd1) begin
                    $display("[ACT4-SOC-PASS] cycle=%0d tohost=0x%016h exit=0x%08h",
                        cycle_count, tohost_address, exit_code);
                end else begin
                    $display("[ACT4-SOC-FAIL] cycle=%0d tohost=0x%016h exit=0x%08h mem_addr=0x%016h wdata=0x%016h wmask=0x%02h",
                        cycle_count, tohost_address, exit_code,
                        retire_mem_addr, retire_mem_wdata, retire_mem_wmask);
                end
                $finish;
            end
            if (configuration_valid && (cycle_count >= max_cycles)) begin
                $display("[ACT4-SOC-TIMEOUT] cycle=%0d tohost=0x%016h",
                    cycle_count, tohost_address);
                $finish;
            end
        end
    end
endmodule

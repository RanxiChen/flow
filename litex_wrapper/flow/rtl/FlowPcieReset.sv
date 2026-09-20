// Reset requests cross independently; no asynchronous combinational fan-in
// precedes a synchronizer. The board reference clock must run continuously.
module FlowPcieReset (
    input wire ref_clk, sys_clk, pcie_clk,
    input wire pcie_reset_n, button_reset, soft_reset, sys_reset,
    output reg pll_reset = 1'b1,
    output wire bridge_reset
);
    // LiteX's software request is a combinational decode in the sys domain.
    // Register it before crossing. One sys cycle is sufficient: assertion at
    // the receiving synchronizer is asynchronous, even if clocks later stop.
    reg soft_request = 0;
    always @(posedge sys_clk) soft_request <= soft_reset;

    (* ASYNC_REG = "TRUE" *) reg [2:0] ref_pcie_ok = 0;
    (* ASYNC_REG = "TRUE" *) reg [2:0] ref_button_reset = 3'b111;
    (* ASYNC_REG = "TRUE" *) reg [2:0] ref_soft_reset = 3'b111;
    always @(posedge ref_clk or negedge pcie_reset_n)
        if (!pcie_reset_n) ref_pcie_ok <= 0;
        else ref_pcie_ok <= {ref_pcie_ok[1:0],1'b1};
    always @(posedge ref_clk or posedge button_reset)
        if (button_reset) ref_button_reset <= 3'b111;
        else ref_button_reset <= {ref_button_reset[1:0],1'b0};
    always @(posedge ref_clk or posedge soft_request)
        if (soft_request) ref_soft_reset <= 3'b111;
        else ref_soft_reset <= {ref_soft_reset[1:0],1'b0};
    // Fan-in is now wholly synchronous to the always-on reference clock.
    always @(posedge ref_clk)
        pll_reset <= !ref_pcie_ok[2] | ref_button_reset[2] | ref_soft_reset[2];

    (* ASYNC_REG = "TRUE" *) reg [2:0] bridge_pcie_ok = 0;
    (* ASYNC_REG = "TRUE" *) reg [2:0] bridge_sys_reset = 3'b111;
    always @(posedge pcie_clk or negedge pcie_reset_n)
        if (!pcie_reset_n) bridge_pcie_ok <= 0;
        else bridge_pcie_ok <= {bridge_pcie_ok[1:0],1'b1};
    always @(posedge pcie_clk or posedge sys_reset)
        if (sys_reset) bridge_sys_reset <= 3'b111;
        else bridge_sys_reset <= {bridge_sys_reset[1:0],1'b0};
    // Either source asserts reset without a PCIe clock; both must release
    // synchronously before the AXI source side can leave reset.
    assign bridge_reset = !bridge_pcie_ok[2] | bridge_sys_reset[2];
endmodule

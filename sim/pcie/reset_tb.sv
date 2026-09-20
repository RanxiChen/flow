`timescale 1ns/1ps
module reset_tb;
    reg ref_clk=0, sys_clk=0, pcie_clk=0;
    reg pcie_reset_n=0, button_reset=0, soft_reset=0, sys_reset=1;
    reg sys_running=1, pcie_running=1;
    wire pll_reset, bridge_reset;
    FlowPcieReset dut(.*);
    always #4 ref_clk=~ref_clk;
    always #5 if(sys_running) sys_clk=~sys_clk; else sys_clk=0;
    always #2 if(pcie_running) pcie_clk=~pcie_clk; else pcie_clk=0;
    task ref_cycles(input integer n);
        begin repeat(n) begin @(posedge ref_clk); #1; end end
    endtask
    task pcie_cycles(input integer n);
        begin repeat(n) begin @(posedge pcie_clk); #0.1; end end
    endtask
    task release_soc;
        begin
            ref_cycles(6);
            if(pll_reset) $fatal(1,"PLL reset stuck");
            sys_reset=0;
            pcie_cycles(4);
            if(bridge_reset) $fatal(1,"bridge reset stuck");
        end
    endtask
    initial begin
        ref_cycles(5);
        if(!pll_reset || !bridge_reset) $fatal(1,"reset absent before PCIe clock/reset ready");
        pcie_reset_n=1;
        release_soc();
        // Clock gone: assertion and ref-domain request must still work.
        @(negedge pcie_clk); pcie_running=0;
        #1; pcie_reset_n=0; #0.1;
        if(!bridge_reset) $fatal(1,"reset needs PCIe clock to assert");
        ref_cycles(2);
        if(!pll_reset) $fatal(1,"PCIe request not reaching PLL");
        sys_reset=1;
        @(negedge sys_clk); sys_running=0;
        pcie_reset_n=1; ref_cycles(6);
        if(pll_reset) $fatal(1,"reset release depends on stopped sys clock");
        sys_running=1; sys_reset=0;
        ref_cycles(3);
        if(!bridge_reset) $fatal(1,"bridge released without PCIe edges");
        pcie_running=1; pcie_cycles(4);
        if(bridge_reset) $fatal(1,"bridge failed to release");
        // Software reset pulse, followed by the board button.
        @(negedge sys_clk); soft_reset=1;
        @(negedge sys_clk); soft_reset=0;
        ref_cycles(1);
        if(!pll_reset) $fatal(1,"software pulse lost");
        sys_reset=1; release_soc();
        button_reset=1; ref_cycles(2);
        if(!pll_reset) $fatal(1,"button reset lost");
        sys_reset=1; button_reset=0; release_soc();
        // Overlap two sources. One releasing cannot release the whole tree.
        pcie_reset_n=0; button_reset=1; ref_cycles(3);
        pcie_reset_n=1; ref_cycles(6);
        if(!pll_reset) $fatal(1,"overlapping source released early");
        sys_reset=1; button_reset=0; release_soc();
        $display("FLOW_PCIE_RESET_PASS"); $finish;
    end
    initial begin #20000; $fatal(1,"timeout"); end
endmodule

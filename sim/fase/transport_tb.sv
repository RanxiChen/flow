`timescale 1ns/1ps
module transport_tb;
    reg sys_clk=0, reset=1, core_reset=0, tck=0, sel=0, capture=0, shift=0, update=0, tap_reset=0, tdi=0;
    wire tdo, cmd_valid, rsp_ready;
    reg cmd_ready=0, rsp_valid=0, rsp_error=0;
    reg [63:0] rsp_data=0;
    wire [7:0] cmd_opcode, debug_flags;
    wire [5:0] cmd_index;
    wire [63:0] cmd_data, cmd_pc;
    wire [15:0] debug_received, debug_dispatched, debug_responded, debug_tag;
    FlowFaseJtagTransport dut (.*);
    always #5 sys_clk=~sys_clk;
    task tick;
        begin #7; tck=1; #11; tck=0; #5; end
    endtask
    task scan_frame(input [191:0] frame, output [191:0] reply);
        integer i;
        begin
            sel=1; capture=1; tick(); capture=0;
            shift=1;
            for(i=0;i<192;i=i+1) begin
                tdi=frame[i]; reply[i]=tdo; tick();
            end
            shift=0; update=1; tick(); update=0; sel=0;
        end
    endtask
    function [191:0] packet(input [15:0] tag, input [63:0] data);
        packet={16'hfa5e,18'b0,tag,64'h80000400,data,6'd5,8'd4};
    endfunction
    reg [191:0] reply;
    integer n;
    initial begin
        #20; reset=0; repeat(4) tick();
        scan_frame(packet(1,64'h123456789abcdef0),reply);
        repeat(6) @(negedge sys_clk);
        if(!cmd_valid || cmd_opcode!=4 || cmd_index!=5 || cmd_data!=64'h123456789abcdef0 || cmd_pc!=64'h80000400)
            $fatal(1,"command corrupted or missing");
        // Busy submission must neither overwrite data nor execute twice.
        scan_frame(packet(2,99),reply);
        if(!reply[66] || cmd_data!=64'h123456789abcdef0 || debug_dispatched!=0)
            $fatal(1,"backpressure/overwrite failure");
        // TAP reset does not cancel a submitted operation.
        tap_reset=1; tick(); tap_reset=0;
        @(negedge sys_clk); cmd_ready=1;
        @(negedge sys_clk); cmd_ready=0;
        repeat(3) @(negedge sys_clk);
        rsp_valid=1; rsp_data=64'hfedcba9876543210;
        @(negedge sys_clk); rsp_valid=0;
        // JTAG clock is stopped while system captures the response.
        #200;
        if(debug_dispatched!=1 || debug_responded!=1) $fatal(1,"duplicate or missing transaction");
        repeat(4) tick(); scan_frame(0,reply);
        if(!reply[65] || reply[66] || !reply[67] || reply[83:68]!=1 || reply[63:0]!=64'hfedcba9876543210)
            $fatal(1,"response corrupted");
        // Exercise the opposite toggle polarity, error response and variable delay.
        scan_frame(packet(2,17),reply);
        repeat(8) @(negedge sys_clk);
        if(!cmd_valid || cmd_data!=17) $fatal(1,"second command missing");
        cmd_ready=1; @(negedge sys_clk); cmd_ready=0;
        repeat(4) @(negedge sys_clk);
        rsp_valid=1; rsp_error=1; rsp_data=23;
        @(negedge sys_clk); rsp_valid=0;
        repeat(4) tick(); scan_frame(0,reply);
        if(!reply[65] || !reply[64] || reply[83:68]!=2 || reply[63:0]!=23)
            $fatal(1,"second response missing");
        // System reset during an outstanding command cancels both mailbox ends.
        scan_frame(packet(3,55),reply);
        #31; reset=1; #31; reset=0;
        repeat(4) tick(); scan_frame(0,reply);
        if(reply[66:65]!=0 || debug_received!=0 || cmd_valid) $fatal(1,"reset created phantom command");
        scan_frame(packet(4,77),reply);
        // CPU-only reset must clear both mailbox ends with TCK stopped.
        #23; core_reset=1; #30; core_reset=0;
        repeat(6) @(negedge sys_clk);
        if(!dut.trst || cmd_valid) $fatal(1,"core reset lost while TCK stopped");
        repeat(4) tick(); scan_frame(0,reply);
        if(reply[66:65]!=0 || debug_received!=0 || cmd_valid) $fatal(1,"core reset created phantom command");
        // A partial scan must not submit.
        sel=1; capture=1; tick(); capture=0; shift=1;
        repeat(12) tick(); shift=0; update=1; tick(); update=0; sel=0;
        #100;
        if(debug_received!=0) $fatal(1,"partial scan submitted");
        $display("FASE_JTAG_TRANSPORT_PASS"); $finish;
    end
    initial begin #1000000; $fatal(1,"timeout"); end
endmodule

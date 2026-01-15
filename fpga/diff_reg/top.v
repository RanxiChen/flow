module top(
    input clk125_p,
    input clk125_n,
    input cpu_reset,
    output user_led        
);
wire sysclk;
IBUFDS inst0(
    .I(clk125_p),
    .IB(clk125_n),
    .O(sysclk)
);
(* keep_hierarchy = "yes" *)
DiffRegFile inst1(
    .clock(sysclk),
    .reset(cpu_reset),
    .io_sucess(user_led)
);
endmodule
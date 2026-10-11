`timescale 1ns/1ps
// Check physical-boundary sampling against a synchronous root-clock source.
// Launch 0.2..2 ns after each rising root edge, matching frontend.xdc. Internal
// BUFG-distributed clocks are delayed 2..4 ns. The intervening falling sampler
// must deliver exactly the value used at the corresponding root rising edge.
module phase_checker #(parameter realtime SKEW=3.0)(
  input logic root_clock, input logic [255:0] data,
  output int checks, output int raw_mismatches
);
  wire dut_clock;
  assign #(SKEW) dut_clock=root_clock;
  logic [255:0] sampled='0;
  logic [255:0] expected='0;
  logic [255:0] received='0;
  always @(posedge root_clock) expected<=data;
  always @(negedge dut_clock) sampled<=data;
  always @(posedge dut_clock) begin
    received<=sampled;
    #0.001;
    if(received!==expected) $fatal(1,"phase input corruption skew=%0.3f",SKEW);
    checks++;
    // Negative control: an unadapted input sees the newly launched value.
    if(data!==expected) raw_mismatches++;
  end
endmodule

module phase_interface_test;
  logic clock=0;
  always #5 clock=~clock;
  logic [255:0] data='0;
  int checks[8];
  int raw_mismatches[8];
  for(genvar i=0;i<8;i++) begin:phase
    phase_checker #(.SKEW(2.0+(2.0*i/7.0))) c(clock,data,checks[i],raw_mismatches[i]);
  end
  initial begin
    int seed=32'h77123bcd;
    int ignored;
    ignored=$urandom(seed);
    for(int cycle=0;cycle<10000;cycle++) begin
      @(posedge clock);
      // Stable multi-cycle bursts model payload held under backpressure.
      #(0.2+0.1*$urandom_range(0,18));
      if($urandom_range(0,3)!=0) begin
        for(int word_index=0;word_index<8;word_index++)
          data[word_index*32+:32]=$urandom();
      end
    end
    repeat(2) @(posedge clock);
    #4.1;
    for(int i=0;i<8;i++) begin
      if(checks[i]<10000 || raw_mismatches[i]<1000)
        $fatal(1,"vacuous phase check %0d checks=%0d raw=%0d",i,checks[i],raw_mismatches[i]);
    end
    $display("PHASE_INTERFACE_PASS: 8 clock phases, >=80000 vector comparisons; raw negative control failed as expected");
    $finish;
  end
endmodule

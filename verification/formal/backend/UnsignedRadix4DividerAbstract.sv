// T01 step-3 review decision: abstract arithmetic core, concrete wrapper.
// Latency is measured in clock edges after acceptance: 0 completes on the
// acceptance edge, 1..34 complete on a later edge. out_valid is registered,
// just as in the concrete core. Reset/flush cancel the completion obligation.
module UnsignedRadix4Divider(
  input clock,
  input reset,
  input io_flush,
  input io_in_valid,
  input [63:0] io_dividend,
  input [63:0] io_divisor,
  output io_busy,
  output io_out_valid,
  output [63:0] io_quotient,
  output [63:0] io_remainder
);
  (* anyseq *) reg finish_now;
  (* anyseq *) reg [63:0] arbitrary_quotient;
  (* anyseq *) reg [63:0] arbitrary_remainder;
  reg active;
  reg [5:0] age;
  reg completed;
  assign io_busy = active;
  assign io_out_valid = completed;
  assign io_quotient = arbitrary_quotient;
  assign io_remainder = arbitrary_remainder;

  always @(posedge clock) begin
    if (!reset) begin
      // Preserve the concrete core's request-while-busy assertion.
      assert(!(io_in_valid && active));
      if (!io_flush) begin
        assert(!active || age <= 33);
        // The one new assume approved in the review: a non-canceled request
        // must complete by acceptance+34. Earlier completion is unconstrained.
        assume(!(active && age == 33) || finish_now);
        cover(io_in_valid && finish_now);       // zero waiting edges
        cover(active && age == 33 && finish_now); // maximum 34 waiting edges
      end
    end
    if (reset || io_flush) begin
      active <= 0;
      completed <= 0;
      age <= 0;
    end else begin
      completed <= 0;
      if (io_in_valid && !active) begin
        active <= !finish_now;
        completed <= finish_now;
        age <= 0;
      end else if (active) begin
        if (finish_now) begin
          active <= 0;
          completed <= 1;
        end else begin
          age <= age + 1;
        end
      end
    end
  end
endmodule

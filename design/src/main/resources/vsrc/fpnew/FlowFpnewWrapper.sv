module FlowFpnewWrapper (
  input  logic        clk_i,
  input  logic        reset_i,
  input  logic        flush_i,
  input  logic        in_valid_i,
  output logic        in_ready_o,
  input  logic [63:0] operand_a_i,
  input  logic [63:0] operand_b_i,
  input  logic [63:0] operand_c_i,
  input  logic [2:0]  rnd_mode_i,
  input  logic [3:0]  op_i,
  input  logic        op_mod_i,
  input  logic [2:0]  src_fmt_i,
  input  logic [2:0]  dst_fmt_i,
  input  logic [1:0]  int_fmt_i,
  output logic        out_valid_o,
  input  logic        out_ready_i,
  output logic [63:0] result_o,
  output logic [4:0]  status_o,
  output logic        busy_o
);
  logic unused_tag, unused_early_valid;
  logic [0:0] simd_mask;
  fpnew_pkg::status_t status;
  // Based on CVA6's per-operation pipeline configuration (6348e9e68467).
  // FP32 ADDMUL uses input + internal + output stages, separating its
  // normalization/rounding/status path from outer result arbitration.
  // FP64 FMA adds a full-precision pre-adder stage in our CVFPU fork.
  // CONV adds a pre-rounding stage between shifting and rounding in our fork.
  // DISTRIBUTED enables the arithmetic units' internal register boundaries;
  // BEFORE alone only registers inputs and leaves the arithmetic path intact.
  // Breeze's blocking valid/ready protocol waits for the resulting latency.
  localparam fpnew_pkg::fpu_implementation_t FlowImplementation = '{
    PipeRegs:   '{'{3, 4, 1, 1, 1},  // ADDMUL: FP32, FP64, FP16, FP8, FP16alt
                  '{default: 2},    // DIVSQRT (not the total iterative latency)
                  '{default: 1},    // NONCOMP
                  '{default: 4}},   // CONV: input + internal + pre-round + output
    UnitTypes:  '{'{default: fpnew_pkg::PARALLEL},
                  '{default: fpnew_pkg::MERGED},
                  '{default: fpnew_pkg::PARALLEL},
                  '{default: fpnew_pkg::MERGED}},
    PipeConfig: fpnew_pkg::DISTRIBUTED
  };

  assign simd_mask = '1;
  assign status_o = status;

  fpnew_top #(
    .Features       (fpnew_pkg::RV64D),
    .Implementation (FlowImplementation),
    .DivSqrtSel     (fpnew_pkg::THMULTI),
    .TagType        (logic),
    .TrueSIMDClass  (0),
    .EnableSIMDMask (0)
  ) i_fpnew (
    .clk_i,
    .rst_ni         (~reset_i),
    .operands_i     ({operand_c_i, operand_b_i, operand_a_i}),
    .rnd_mode_i     (fpnew_pkg::roundmode_e'(rnd_mode_i)),
    .op_i           (fpnew_pkg::operation_e'(op_i)),
    .op_mod_i,
    .src_fmt_i      (fpnew_pkg::fp_format_e'(src_fmt_i)),
    .dst_fmt_i      (fpnew_pkg::fp_format_e'(dst_fmt_i)),
    .int_fmt_i      (fpnew_pkg::int_format_e'(int_fmt_i)),
    .vectorial_op_i (1'b0),
    .tag_i          (1'b0),
    .simd_mask_i    (simd_mask),
    .in_valid_i,
    .in_ready_o,
    .flush_i,
    .result_o,
    .status_o       (status),
    .tag_o          (unused_tag),
    .out_valid_o,
    .out_ready_i,
    .busy_o,
    .early_valid_o  (unused_early_valid)
  );
endmodule

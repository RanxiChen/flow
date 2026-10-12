module ZveFpnewWrapper #(parameter int TAG_WIDTH=16, KIND=2) (
  input  logic        clk_i,
  input  logic        reset_i,
  input  logic        flush_i,
  input  logic        in_valid_i,
  input logic [TAG_WIDTH-1:0] tag_i,
  output logic [TAG_WIDTH-1:0] tag_o,
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
  logic unused_early_valid;
  logic [0:0] simd_mask;
  fpnew_pkg::status_t arithmetic_status;
  localparam int WIDTH = (KIND == 2 ? 64 : 32);
  localparam fpnew_pkg::fpu_features_t ZveFeatures = '{
    Width: 32, EnableVectors: 1'b0, EnableNanBox: 1'b0,
    FpFmtMask: 5'b10000, IntFmtMask: 4'b1110
  };
  logic [WIDTH-1:0] result_bits;
  logic arithmetic_ready, arithmetic_valid, arithmetic_busy;
  logic [TAG_WIDTH-1:0] arithmetic_tag;
  logic cast_ready, cast_valid, cast_busy;
  logic [31:0] cast_result;
  logic [TAG_WIDTH-1:0] cast_tag;
  fpnew_pkg::status_t cast_status;
  wire use_cast = KIND == 1 && (op_i == 4'(fpnew_pkg::F2I) || op_i == 4'(fpnew_pkg::I2F));
  logic prefer_cast;
  logic selection_held, held_cast;
  wire choose_cast = selection_held ? held_cast : cast_valid && (!arithmetic_valid || prefer_cast);
  always_ff @(posedge clk_i) begin
    if (reset_i) begin
      prefer_cast <= 1'b0; selection_held <= 1'b0; held_cast <= 1'b0;
    end else if (out_valid_o && out_ready_i) begin
      prefer_cast <= !choose_cast; selection_held <= 1'b0;
    end else if (out_valid_o && !out_ready_i) begin
      selection_held <= 1'b1; held_cast <= choose_cast;
    end
  end
  // KIND=0: ADDMUL only; KIND=1: conversions/non-computational; KIND=2: general.
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
    UnitTypes:  '{'{default: (KIND == 1 ? fpnew_pkg::DISABLED : fpnew_pkg::PARALLEL)},
                  '{default: (KIND == 2 ? fpnew_pkg::MERGED : fpnew_pkg::DISABLED)},
                  '{default: (KIND == 0 ? fpnew_pkg::DISABLED : fpnew_pkg::PARALLEL)},
                  '{default: (KIND == 2 ? fpnew_pkg::MERGED : fpnew_pkg::DISABLED)}},
    PipeConfig: fpnew_pkg::DISTRIBUTED
  };

  assign simd_mask = '1;
  assign in_ready_o = use_cast ? cast_ready : arithmetic_ready;
  assign out_valid_o = arithmetic_valid || cast_valid;
  assign tag_o = choose_cast ? cast_tag : arithmetic_tag;
  assign status_o = choose_cast ? cast_status : arithmetic_status;
  assign busy_o = arithmetic_busy || cast_busy;

  fpnew_top #(
    .Features       (KIND == 2 ? fpnew_pkg::RV64D : ZveFeatures),
    .Implementation (FlowImplementation),
    .DivSqrtSel     (fpnew_pkg::THMULTI),
    .TagType        (logic [TAG_WIDTH-1:0]),
    .TrueSIMDClass  (0),
    .EnableSIMDMask (0)
  ) i_fpnew (
    .clk_i,
    .rst_ni         (~reset_i),
    .operands_i     ({operand_c_i[WIDTH-1:0], operand_b_i[WIDTH-1:0], operand_a_i[WIDTH-1:0]}),
    .rnd_mode_i     (fpnew_pkg::roundmode_e'(rnd_mode_i)),
    .op_i           (fpnew_pkg::operation_e'(op_i)),
    .op_mod_i,
    .src_fmt_i      (fpnew_pkg::fp_format_e'(src_fmt_i)),
    .dst_fmt_i      (fpnew_pkg::fp_format_e'(dst_fmt_i)),
    .int_fmt_i      (fpnew_pkg::int_format_e'(int_fmt_i)),
    .vectorial_op_i (1'b0),
    .tag_i          (tag_i),
    .simd_mask_i    (simd_mask),
    .in_valid_i      (in_valid_i && !use_cast),
    .in_ready_o     (arithmetic_ready),
    .flush_i,
    .result_o       (result_bits),
    .status_o       (arithmetic_status),
    .tag_o          (arithmetic_tag),
    .out_valid_o    (arithmetic_valid),
    .out_ready_i    (out_ready_i && !choose_cast),
    .busy_o         (arithmetic_busy),
    .early_valid_o  (unused_early_valid)
  );
  // fpnew_top masks integer formats against enabled FP widths. With FP32 only,
  // its generic CONV slice silently removes INT16. Instantiate the original
  // registered cast primitive directly, preserving INT16 without an FP16 ALU.
  if (KIND == 1) begin : local_conversion
    logic unused_extension, unused_mask, unused_aux, unused_early;
    fpnew_cast_multi #(
      .FpFmtConfig (5'b10000), .IntFmtConfig (4'b0110),
      .NumPipeRegs (4), .PipeConfig (fpnew_pkg::DISTRIBUTED),
      .TagType (logic [TAG_WIDTH-1:0]), .AuxType (logic)
    ) i_cast (
      .clk_i, .rst_ni (~reset_i), .operands_i (operand_a_i[31:0]),
      .is_boxed_i (5'b11111), .rnd_mode_i (fpnew_pkg::roundmode_e'(rnd_mode_i)),
      .op_i (fpnew_pkg::operation_e'(op_i)), .op_mod_i,
      .src_fmt_i (fpnew_pkg::fp_format_e'(src_fmt_i)),
      .dst_fmt_i (fpnew_pkg::fp_format_e'(dst_fmt_i)),
      .int_fmt_i (fpnew_pkg::int_format_e'(int_fmt_i)), .tag_i, .mask_i (1'b1), .aux_i (1'b0),
      .in_valid_i (in_valid_i && use_cast), .in_ready_o (cast_ready), .flush_i,
      .result_o (cast_result), .status_o (cast_status), .extension_bit_o (unused_extension),
      .tag_o (cast_tag), .mask_o (unused_mask), .aux_o (unused_aux),
      .out_valid_o (cast_valid), .out_ready_i (out_ready_i && choose_cast),
      .busy_o (cast_busy), .reg_ena_i ('0), .early_out_valid_o (unused_early)
    );
  end else begin : no_local_conversion
    assign cast_ready=1'b0; assign cast_valid=1'b0; assign cast_busy=1'b0;
    assign cast_result='0; assign cast_tag='0; assign cast_status='0;
  end
  assign result_o = choose_cast ? {32'hffffffff, cast_result} :
    (WIDTH == 64 ? 64'(result_bits) : {32'hffffffff, result_bits[31:0]});
endmodule

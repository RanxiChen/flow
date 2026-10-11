
# Create Project

create_project -force -name xilinx_kcu105 -part xcku040-ffva1156-2-e
set_msg_config -id {Common 17-55} -new_severity {Warning}

# Add project commands


# Add Sources

read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-0/cf_math_pkg.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-0/cf_math_pkg.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-1/lzc.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-1/lzc.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-2/rr_arb_tree.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-2/rr_arb_tree.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-3/fpnew_pkg.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-3/fpnew_pkg.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-4/fpnew_cast_multi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-4/fpnew_cast_multi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-5/fpnew_classifier.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-5/fpnew_classifier.sv}]
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-6/gated_clk_cell.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-7/pa_fdsu_ctrl.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-8/pa_fdsu_ff1.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-9/pa_fdsu_pack_single.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-10/pa_fdsu_prepare.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-11/pa_fdsu_round_single.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-12/pa_fdsu_special.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-13/pa_fdsu_srt_single.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-14/pa_fdsu_top.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-15/pa_fpu_dp.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-16/pa_fpu_frbus.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-17/pa_fpu_src_type.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-18/ct_vfdsu_ctrl.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-19/ct_vfdsu_double.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-20/ct_vfdsu_ff1.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-21/ct_vfdsu_pack.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-22/ct_vfdsu_prepare.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-23/ct_vfdsu_round.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-24/ct_vfdsu_scalar_dp.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-25/ct_vfdsu_srt_radix16_bound_table.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-26/ct_vfdsu_srt_radix16_with_sqrt.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-27/ct_vfdsu_srt.v}
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-28/ct_vfdsu_top.v}
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-29/fpnew_divsqrt_th_32.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-29/fpnew_divsqrt_th_32.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-30/fpnew_divsqrt_th_64_multi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-30/fpnew_divsqrt_th_64_multi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-31/fpnew_divsqrt_multi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-31/fpnew_divsqrt_multi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-32/fpnew_fma.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-32/fpnew_fma.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-33/fpnew_fma_multi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-33/fpnew_fma_multi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-34/fpnew_noncomp.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-34/fpnew_noncomp.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-35/fpnew_opgroup_block.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-35/fpnew_opgroup_block.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-36/fpnew_opgroup_fmt_slice.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-36/fpnew_opgroup_fmt_slice.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-37/fpnew_opgroup_multifmt_slice.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-37/fpnew_opgroup_multifmt_slice.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-38/fpnew_rounding.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-38/fpnew_rounding.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-39/fpnew_top.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-39/fpnew_top.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-40/FlowFpnewWrapper.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-40/FlowFpnewWrapper.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-41/ALU.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-41/ALU.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-42/Axi4LiteArbiter.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-42/Axi4LiteArbiter.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-43/BRU.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-43/BRU.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-44/BreezeAmoAlu.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-44/BreezeAmoAlu.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-45/BreezeBTB.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-45/BreezeBTB.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-46/BreezeBackend.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-46/BreezeBackend.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-47/BreezeCluster.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-47/BreezeCluster.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-48/BreezeClusterAxi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-48/BreezeClusterAxi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-49/BreezeCompressedDecoder.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-49/BreezeCompressedDecoder.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-50/BreezeFetchTranslator.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-50/BreezeFetchTranslator.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-51/BreezeFpDecoder.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-51/BreezeFpDecoder.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-52/BreezeFpRegFile.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-52/BreezeFpRegFile.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-53/BreezeFrontend.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-53/BreezeFrontend.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-54/BreezeFrontendBoundary.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-54/BreezeFrontendBoundary.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-55/BreezeInstrRealigner.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-55/BreezeInstrRealigner.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-56/BreezePHT.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-56/BreezePHT.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-57/BreezePerformanceCounters.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-57/BreezePerformanceCounters.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-58/BreezePmpChecker.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-58/BreezePmpChecker.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-59/BreezePmpChecker_2.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-59/BreezePmpChecker_2.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-60/CSRFile.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-60/CSRFile.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-61/Decoder.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-61/Decoder.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-62/DivUnit.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-62/DivUnit.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-63/FetchBuffer.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-63/FetchBuffer.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-64/FetchTlbClient.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-64/FetchTlbClient.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-65/FpUnit.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-65/FpUnit.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-66/ImmGen.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-66/ImmGen.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-67/JAU.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-67/JAU.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-68/L1DCache.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-68/L1DCache.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-69/L1DMiss.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-69/L1DMiss.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-70/L1DMmio.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-70/L1DMmio.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-71/L1DProbe.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-71/L1DProbe.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-72/L1ICache.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-72/L1ICache.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-73/L1IClient.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-73/L1IClient.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-74/L2Home.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-74/L2Home.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-75/L2MemEngine.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-75/L2MemEngine.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-76/L2ProbeEngine.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-76/L2ProbeEngine.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-77/L2Slots.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-77/L2Slots.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-78/MiniDecode.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-78/MiniDecode.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-79/MulDspTile.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-79/MulDspTile.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-80/MulUnit.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-80/MulUnit.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-81/PMAChecker.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-81/PMAChecker.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-82/Queue1_CoherenceRspDown.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-82/Queue1_CoherenceRspDown.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-83/Queue2_1_Anon.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-83/Queue2_1_Anon.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-84/Queue2_BreezeCacheRespIO.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-84/Queue2_BreezeCacheRespIO.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-85/Queue2_CoherenceRspDown.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-85/Queue2_CoherenceRspDown.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-86/Queue2_FpUnit_Anon.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-86/Queue2_FpUnit_Anon.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-87/Queue2_FpUnit_Anon_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-87/Queue2_FpUnit_Anon_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-88/Queue2_L1DLate.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-88/Queue2_L1DLate.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-89/Queue2_UInt1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-89/Queue2_UInt1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-90/Queue6_FrontendFetchBundle.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-90/Queue6_FrontendFetchBundle.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-91/RRArbiter1_Bool.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-91/RRArbiter1_Bool.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-92/RRArbiter2_UInt1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-92/RRArbiter2_UInt1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-93/RV64IZicsrDecoder.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-93/RV64IZicsrDecoder.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-94/RegFile.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-94/RegFile.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-95/Scoreboard.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-95/Scoreboard.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-96/SdpSram.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-96/SdpSram.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-97/SdpSramBlackBox.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-97/SdpSramBlackBox.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-98/SdpSram_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-98/SdpSram_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-99/SdpSram_2.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-99/SdpSram_2.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-100/Sv39Mmu.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-100/Sv39Mmu.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-101/Sv39Ptw.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-101/Sv39Ptw.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-102/Sv39Tlb.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-102/Sv39Tlb.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-103/Sv39Tlb_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-103/Sv39Tlb_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-104/Sv39WalkCache.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-104/Sv39WalkCache.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-105/Sv39WalkCache_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-105/Sv39WalkCache_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-106/UnsignedRadix4Divider.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-106/UnsignedRadix4Divider.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-107/Writeback.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-107/Writeback.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-108/content_32x64.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-108/content_32x64.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-109/content_32x64_0.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-109/content_32x64_0.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-110/data_256x64.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-110/data_256x64.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-111/data_512x64.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-111/data_512x64.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-112/flowSRAM.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-112/flowSRAM.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-113/flowSRAM_2.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-113/flowSRAM_2.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-114/flowSRAM_4.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-114/flowSRAM_4.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-115/mem_128x256.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-115/mem_128x256.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-116/mem_128x52.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-116/mem_128x52.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-117/mem_2x91.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-117/mem_2x91.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-118/mem_64x256.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-118/mem_64x256.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-119/mem_64x53.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-119/mem_64x53.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-120/ram_2x1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-120/ram_2x1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-121/ram_2x213.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-121/ram_2x213.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-122/ram_2x261.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-122/ram_2x261.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-123/ram_2x28.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-123/ram_2x28.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-124/ram_2x71.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-124/ram_2x71.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-125/ram_2x80.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-125/ram_2x80.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-126/ram_2x98.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-126/ram_2x98.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-127/ram_6x210.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-127/ram_6x210.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-128/tags_16x22.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-128/tags_16x22.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-129/tags_8x23.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-129/tags_8x23.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-130/FlowClint.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-130/FlowClint.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-131/FlowPlic.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/source-131/FlowPlic.sv}]
read_verilog {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/gateware/xilinx_kcu105.v}

# Add EDIFs


# Add IPs


# Add constraints

read_xdc xilinx_kcu105.xdc
set_property PROCESSING_ORDER EARLY [get_files xilinx_kcu105.xdc]

# Add pre-synthesis commands

set_param general.maxThreads 4
set_msg_config -id {Synth 8-4767} -limit 100000

# Synthesis

synth_design -directive default -top xilinx_kcu105 -part xcku040-ffva1156-2-e -include_dirs {/home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/include-0}

# Synthesis report

report_timing_summary -file xilinx_kcu105_timing_synth.rpt
report_utilization -hierarchical -file xilinx_kcu105_utilization_hierarchical_synth.rpt
report_utilization -file xilinx_kcu105_utilization_synth.rpt
write_checkpoint -force xilinx_kcu105_synth.dcp

# Add pre-optimize commands

source /home/chen/FUN/flow-runs/be2-c735fbd-single-default/source-snapshot/soc2-bram-gate.tcl

# Optimize design

opt_design -directive default

# Add pre-placement commands


# Placement

place_design -directive default

# Placement report

report_utilization -hierarchical -file xilinx_kcu105_utilization_hierarchical_place.rpt
report_utilization -file xilinx_kcu105_utilization_place.rpt
report_io -file xilinx_kcu105_io.rpt
report_control_sets -verbose -file xilinx_kcu105_control_sets.rpt
report_clock_utilization -file xilinx_kcu105_clock_utilization.rpt
write_checkpoint -force xilinx_kcu105_place.dcp

# Add pre-routing commands


# Routing

route_design -directive default
phys_opt_design -directive default
write_checkpoint -force xilinx_kcu105_route.dcp

# Routing report

report_timing_summary -no_header -no_detailed_paths
report_route_status -file xilinx_kcu105_route_status.rpt
report_drc -file xilinx_kcu105_drc.rpt
report_timing_summary -datasheet -max_paths 10 -file xilinx_kcu105_timing.rpt
report_power -file xilinx_kcu105_power.rpt

# Bitstream generation

# BE2 scope ends at routed evidence; no bitstream.
report_timing_summary -file be2-timing-summary.rpt
report_timing -max_paths 20 -path_type full -file be2-worst-20.rpt
report_utilization -hierarchical -file be2-utilization-hierarchical.rpt
report_utilization -file be2-utilization.rpt

# End

quit
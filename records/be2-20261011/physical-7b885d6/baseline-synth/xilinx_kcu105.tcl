
# Create Project

create_project -force -name xilinx_kcu105 -part xcku040-ffva1156-2-e
set_msg_config -id {Common 17-55} -new_severity {Warning}

# Add project commands


# Add Sources

read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-0/cf_math_pkg.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-0/cf_math_pkg.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-1/lzc.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-1/lzc.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-2/rr_arb_tree.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-2/rr_arb_tree.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-3/fpnew_pkg.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-3/fpnew_pkg.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-4/fpnew_cast_multi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-4/fpnew_cast_multi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-5/fpnew_classifier.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-5/fpnew_classifier.sv}]
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-6/gated_clk_cell.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-7/pa_fdsu_ctrl.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-8/pa_fdsu_ff1.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-9/pa_fdsu_pack_single.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-10/pa_fdsu_prepare.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-11/pa_fdsu_round_single.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-12/pa_fdsu_special.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-13/pa_fdsu_srt_single.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-14/pa_fdsu_top.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-15/pa_fpu_dp.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-16/pa_fpu_frbus.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-17/pa_fpu_src_type.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-18/ct_vfdsu_ctrl.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-19/ct_vfdsu_double.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-20/ct_vfdsu_ff1.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-21/ct_vfdsu_pack.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-22/ct_vfdsu_prepare.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-23/ct_vfdsu_round.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-24/ct_vfdsu_scalar_dp.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-25/ct_vfdsu_srt_radix16_bound_table.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-26/ct_vfdsu_srt_radix16_with_sqrt.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-27/ct_vfdsu_srt.v}
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-28/ct_vfdsu_top.v}
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-29/fpnew_divsqrt_th_32.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-29/fpnew_divsqrt_th_32.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-30/fpnew_divsqrt_th_64_multi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-30/fpnew_divsqrt_th_64_multi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-31/fpnew_divsqrt_multi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-31/fpnew_divsqrt_multi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-32/fpnew_fma.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-32/fpnew_fma.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-33/fpnew_fma_multi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-33/fpnew_fma_multi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-34/fpnew_noncomp.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-34/fpnew_noncomp.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-35/fpnew_opgroup_block.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-35/fpnew_opgroup_block.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-36/fpnew_opgroup_fmt_slice.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-36/fpnew_opgroup_fmt_slice.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-37/fpnew_opgroup_multifmt_slice.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-37/fpnew_opgroup_multifmt_slice.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-38/fpnew_rounding.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-38/fpnew_rounding.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-39/fpnew_top.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-39/fpnew_top.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-40/FlowFpnewWrapper.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-40/FlowFpnewWrapper.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-41/ALU.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-41/ALU.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-42/Axi4LiteArbiter.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-42/Axi4LiteArbiter.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-43/BRU.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-43/BRU.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-44/BreezeAmoAlu.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-44/BreezeAmoAlu.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-45/BreezeBTB.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-45/BreezeBTB.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-46/BreezeBackend.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-46/BreezeBackend.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-47/BreezeBackend_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-47/BreezeBackend_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-48/BreezeBackend_2.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-48/BreezeBackend_2.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-49/BreezeBackend_3.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-49/BreezeBackend_3.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-50/BreezeCluster.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-50/BreezeCluster.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-51/BreezeClusterAxi.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-51/BreezeClusterAxi.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-52/BreezeCompressedDecoder.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-52/BreezeCompressedDecoder.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-53/BreezeFetchTranslator.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-53/BreezeFetchTranslator.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-54/BreezeFpDecoder.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-54/BreezeFpDecoder.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-55/BreezeFpRegFile.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-55/BreezeFpRegFile.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-56/BreezeFrontend.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-56/BreezeFrontend.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-57/BreezeFrontendBoundary.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-57/BreezeFrontendBoundary.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-58/BreezeInstrRealigner.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-58/BreezeInstrRealigner.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-59/BreezePHT.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-59/BreezePHT.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-60/BreezePerformanceCounters.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-60/BreezePerformanceCounters.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-61/BreezePmpChecker.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-61/BreezePmpChecker.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-62/BreezePmpChecker_2.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-62/BreezePmpChecker_2.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-63/CSRFile.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-63/CSRFile.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-64/CSRFile_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-64/CSRFile_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-65/CSRFile_2.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-65/CSRFile_2.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-66/CSRFile_3.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-66/CSRFile_3.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-67/Decoder.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-67/Decoder.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-68/DivUnit.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-68/DivUnit.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-69/FetchBuffer.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-69/FetchBuffer.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-70/FetchTlbClient.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-70/FetchTlbClient.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-71/FpUnit.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-71/FpUnit.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-72/ImmGen.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-72/ImmGen.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-73/JAU.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-73/JAU.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-74/L1DCache.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-74/L1DCache.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-75/L1DMiss.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-75/L1DMiss.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-76/L1DMmio.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-76/L1DMmio.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-77/L1DProbe.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-77/L1DProbe.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-78/L1ICache.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-78/L1ICache.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-79/L1IClient.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-79/L1IClient.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-80/L2Home.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-80/L2Home.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-81/L2MemEngine.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-81/L2MemEngine.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-82/L2ProbeEngine.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-82/L2ProbeEngine.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-83/L2Slots.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-83/L2Slots.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-84/MiniDecode.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-84/MiniDecode.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-85/MulDspTile.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-85/MulDspTile.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-86/MulUnit.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-86/MulUnit.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-87/PMAChecker.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-87/PMAChecker.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-88/Queue1_CoherenceRspDown.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-88/Queue1_CoherenceRspDown.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-89/Queue2_1_Anon.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-89/Queue2_1_Anon.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-90/Queue2_BreezeCacheRespIO.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-90/Queue2_BreezeCacheRespIO.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-91/Queue2_CoherenceRspDown.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-91/Queue2_CoherenceRspDown.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-92/Queue2_FpUnit_Anon.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-92/Queue2_FpUnit_Anon.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-93/Queue2_FpUnit_Anon_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-93/Queue2_FpUnit_Anon_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-94/Queue2_UInt1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-94/Queue2_UInt1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-95/Queue6_FrontendFetchBundle.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-95/Queue6_FrontendFetchBundle.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-96/RRArbiter2_UInt1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-96/RRArbiter2_UInt1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-97/RRArbiter4_Bool.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-97/RRArbiter4_Bool.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-98/RV64IZicsrDecoder.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-98/RV64IZicsrDecoder.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-99/RegFile.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-99/RegFile.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-100/Scoreboard.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-100/Scoreboard.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-101/SdpSram.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-101/SdpSram.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-102/SdpSramBlackBox.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-102/SdpSramBlackBox.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-103/SdpSram_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-103/SdpSram_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-104/SdpSram_2.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-104/SdpSram_2.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-105/Sv39Mmu.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-105/Sv39Mmu.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-106/Sv39Ptw.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-106/Sv39Ptw.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-107/Sv39Tlb.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-107/Sv39Tlb.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-108/Sv39Tlb_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-108/Sv39Tlb_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-109/Sv39WalkCache.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-109/Sv39WalkCache.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-110/Sv39WalkCache_1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-110/Sv39WalkCache_1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-111/UnsignedRadix4Divider.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-111/UnsignedRadix4Divider.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-112/Writeback.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-112/Writeback.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-113/content_32x64.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-113/content_32x64.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-114/content_32x64_0.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-114/content_32x64_0.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-115/data_512x64.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-115/data_512x64.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-116/flowSRAM.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-116/flowSRAM.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-117/flowSRAM_4.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-117/flowSRAM_4.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-118/mem_128x256.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-118/mem_128x256.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-119/mem_128x52.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-119/mem_128x52.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-120/mem_2x91.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-120/mem_2x91.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-121/ram_2x1.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-121/ram_2x1.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-122/ram_2x213.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-122/ram_2x213.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-123/ram_2x261.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-123/ram_2x261.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-124/ram_2x28.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-124/ram_2x28.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-125/ram_2x80.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-125/ram_2x80.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-126/ram_2x98.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-126/ram_2x98.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-127/ram_6x210.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-127/ram_6x210.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-128/tags_16x22.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-128/tags_16x22.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-129/FlowClint.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-129/FlowClint.sv}]
read_verilog -v {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-130/FlowPlic.sv}
set_property file_type SystemVerilog [get_files {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/source-130/FlowPlic.sv}]
read_verilog {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/gateware/xilinx_kcu105.v}

# Add EDIFs


# Add IPs


# Add constraints

read_xdc xilinx_kcu105.xdc
set_property PROCESSING_ORDER EARLY [get_files xilinx_kcu105.xdc]

# Add pre-synthesis commands

set_param general.maxThreads 2
set_msg_config -id {Synth 8-4767} -limit 100000

# Synthesis

synth_design -directive default -top xilinx_kcu105 -part xcku040-ffva1156-2-e -include_dirs {/home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/include-0}

# Synthesis report

report_timing_summary -file xilinx_kcu105_timing_synth.rpt
report_utilization -hierarchical -file xilinx_kcu105_utilization_hierarchical_synth.rpt
report_utilization -file xilinx_kcu105_utilization_synth.rpt
write_checkpoint -force xilinx_kcu105_synth.dcp

# Add pre-optimize commands

source /home/chen/FUN/flow-runs/be2-2051dd0-small-baseline/source-snapshot/soc2-bram-gate.tcl

# Optimize design

report_utilization -hierarchical -file be2-utilization-hierarchical.rpt
report_timing_summary -file be2-timing-summary.rpt
exit

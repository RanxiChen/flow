cd {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc}
set_param general.maxThreads 4
create_project -in_memory -part xcku040-ffva1156-2-e
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-0/cf_math_pkg.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-1/lzc.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-2/rr_arb_tree.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-3/fpnew_pkg.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-4/fpnew_cast_multi.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-5/fpnew_classifier.sv}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-6/gated_clk_cell.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-7/pa_fdsu_ctrl.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-8/pa_fdsu_ff1.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-9/pa_fdsu_pack_single.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-10/pa_fdsu_prepare.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-11/pa_fdsu_round_single.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-12/pa_fdsu_special.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-13/pa_fdsu_srt_single.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-14/pa_fdsu_top.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-15/pa_fpu_dp.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-16/pa_fpu_frbus.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-17/pa_fpu_src_type.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-18/ct_vfdsu_ctrl.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-19/ct_vfdsu_double.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-20/ct_vfdsu_ff1.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-21/ct_vfdsu_pack.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-22/ct_vfdsu_prepare.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-23/ct_vfdsu_round.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-24/ct_vfdsu_scalar_dp.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-25/ct_vfdsu_srt_radix16_bound_table.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-26/ct_vfdsu_srt_radix16_with_sqrt.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-27/ct_vfdsu_srt.v}
read_verilog {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-28/ct_vfdsu_top.v}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-29/fpnew_divsqrt_th_32.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-30/fpnew_divsqrt_th_64_multi.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-31/fpnew_divsqrt_multi.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-32/fpnew_fma.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-33/fpnew_fma_multi.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-34/fpnew_noncomp.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-35/fpnew_opgroup_block.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-36/fpnew_opgroup_fmt_slice.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-37/fpnew_opgroup_multifmt_slice.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-38/fpnew_rounding.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-39/fpnew_top.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-40/FlowFpnewWrapper.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-41/ALU.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-42/Axi4LiteArbiter.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-43/BRU.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-44/BreezeAmoAlu.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-45/BreezeBTB.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-46/BreezeBackend.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-47/BreezeCluster.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-48/BreezeClusterAxi.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-49/BreezeCompressedDecoder.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-50/BreezeFetchTranslator.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-51/BreezeFpDecoder.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-52/BreezeFpRegFile.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-53/BreezeFrontend.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-54/BreezeInstrRealigner.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-55/BreezePHT.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-56/BreezePerformanceCounters.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-57/BreezePmpChecker.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-58/CSRFile.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-59/Decoder.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-60/DivUnit.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-61/FetchBuffer.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-62/FetchTlbClient.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-63/FpUnit.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-64/ImmGen.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-65/JAU.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-66/L1DCache.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-67/L1DMiss.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-68/L1DMmio.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-69/L1DProbe.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-70/L1ICache.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-71/L1IClient.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-72/L2Home.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-73/L2MemEngine.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-74/L2ProbeEngine.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-75/L2Slots.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-76/MiniDecode.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-77/MulDspTile.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-78/MulUnit.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-79/PMAChecker.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-80/Queue1_CoherenceRspDown.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-81/Queue2_CoherenceRspDown.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-82/Queue2_UInt1.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-83/Queue6_FrontendFetchBundle.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-84/RRArbiter1_Bool.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-85/RRArbiter2_UInt1.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-86/RV64IZicsrDecoder.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-87/RegFile.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-88/Scoreboard.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-89/SdpSram.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-90/SdpSramBlackBox.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-91/SdpSram_1.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-92/SdpSram_2.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-93/Sv39Mmu.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-94/Sv39Ptw.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-95/Sv39Tlb.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-96/Sv39Tlb_1.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-97/Sv39WalkCache.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-98/Sv39WalkCache_1.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-99/UnsignedRadix4Divider.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-100/Writeback.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-101/content_32x64.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-102/content_32x64_0.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-103/data_512x64.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-104/flowSRAM.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-105/flowSRAM_4.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-106/mem_128x256.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-107/mem_128x52.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-108/mem_8x364.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-109/ram_2x1.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-110/ram_2x261.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-111/ram_6x210.sv}
read_verilog -sv {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/source-112/tags_128x88.sv}
read_xdc {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/clock.xdc}
set started [clock milliseconds]
synth_design -directive AreaOptimized_high -top BreezeCluster -mode out_of_context -part xcku040-ffva1156-2-e -include_dirs {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/rtl-snapshot/include-0}
set out [open synth-elapsed.tsv w]
puts $out "synth_seconds\t[expr {([clock milliseconds] - $started) / 1000.0}]"
close $out
report_timing_summary -file timing-summary.rpt
report_timing -max_paths 20 -nworst 1 -path_type full -file worst-20.rpt
report_utilization -hierarchical -file utilization-hierarchical.rpt
report_utilization -file utilization.rpt
write_checkpoint -force post-synth.dcp
set registers [all_registers -cells]
set starts [filter $registers {NAME =~ mmu/dtlb/* || NAME =~ */mmu/dtlb/*}]
set ends [filter $registers {NAME =~ l1d/cpu2_pmpAllowed* || NAME =~ */l1d/cpu2_pmpAllowed* || NAME =~ l1d/cpu2_pmaAllowed* || NAME =~ */l1d/cpu2_pmaAllowed* || NAME =~ l1d/cpu2_pmaDevice* || NAME =~ */l1d/cpu2_pmaDevice* || NAME =~ l1d/cpu2_pmaAmoOk* || NAME =~ */l1d/cpu2_pmaAmoOk* || NAME =~ l1d/cpu2_pmaRsrvOk* || NAME =~ */l1d/cpu2_pmaRsrvOk* || NAME =~ l1d/cpu2_highAddress* || NAME =~ */l1d/cpu2_highAddress*}]
set out [open tlb-permission-cells.txt w]
puts $out "start_count=[llength $starts] endpoint_count=[llength $ends]"
foreach cell $ends { puts $out [get_property NAME $cell] }
close $out
if {![llength $starts] || ![llength $ends]} { error "Missing dTLB / S1 permission registers" }
report_timing -from $starts -to $ends -max_paths 20 -nworst 1 -path_type full -file tlb-to-s1-permission-worst-20.rpt
set dsps [lsort [get_cells -hierarchical -quiet -filter {REF_NAME =~ DSP48E2}]]
set out [open dsp-pipeline.tsv w]
puts $out "cell\tAREG\tBREG\tMREG\tPREG"
foreach cell $dsps {
    set row [list [get_property NAME $cell]]
    foreach prop {AREG BREG MREG PREG} { lappend row [get_property $prop $cell] }
    puts $out [join $row "\t"]
}
close $out
set shadows [get_cells -hierarchical -quiet -filter {NAME =~ *shadowPmp* || NAME =~ *shadowPma*}]
set out [open shadow-checkers.tsv w]
puts $out "shadow_cell_count\t[llength $shadows]"
foreach cell $shadows { puts $out [get_property NAME $cell] }
close $out

source {/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/queries.tcl}
puts "SOC3B_CLUSTER_OOC_COMPLETED"
exit

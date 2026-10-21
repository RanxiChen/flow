package flow.observe

import chisel3._
import chisel3.util._

/** Stable event schema v1. Deltas are counts, cycles, or latency sums (not Bool-only). */
object BreezeObserveEvent {
  val names: Seq[String] = Seq(
    "active_cycles",
    "instructions",
    "frontend_starved",
    "frontend_backpressure",
    "both_blocked",
    "frontend_issued",
    "queue_empty",
    "queue_low",
    "queue_high",
    "queue_full",
    "fe_wait_itlb",
    "fe_wait_icache",
    "fe_wait_recovery",
    "fe_wait_other",
    "conditional_retired",
    "conditional_direction_wrong",
    "jal_retired",
    "jalr_retired",
    "return_retired",
    "jalr_target_wrong",
    "return_target_wrong",
    "taken_cfi",
    "early_target_missing",
    "early_direction_wrong",
    "early_target_wrong",
    "f2_target_correction",
    "f2_history_correction",
    "backend_branch_redirect",
    "recovery_cycles",
    "recovery_completed",
    "recovery_latency",
    "recovery_superseded",
    "itlb_tag_hit",
    "itlb_tag_miss",
    "itlb_bypass",
    "itlb_fault",
    "itlb_killed",
    "itlb_retry",
    "itlb_pending",
    "iptw_started",
    "iptw_completed",
    "iptw_fault",
    "iptw_killed",
    "iptw_queue_cycles",
    "iptw_service_cycles",
    "iptw_pte_reads",
    "iptw_mem_wait",
    "ic_demand_access",
    "ic_demand_miss",
    "ic_lookup_retry",
    "ic_refill_completed",
    "ic_refill_latency",
    "ic_cancelled_refill",
    "ic_refill_error",
    "ic_valid_eviction",
    "fence_i",
    "ic_refill_wait",
    "il2_request",
    "il2_hit",
    "il2_miss",
    "il2_probe",
    "il2_slot_wait",
    "il2_set_wait",
    "il2_request_wait",
    "new_line",
    "sequential_line",
    "nonsequential_line",
    "next_line_resident",
    "next_line_nonresident",
    "next_line_unknown",
    "recent_eviction_hit_4",
    "recent_eviction_hit_8",
    "recent_eviction_hit_16",
    "backend_fp_wait",
    "backend_lsu_wait",
    "backend_serial_wait",
    "backend_feedback_wait",
    "backend_other_wait",
    "stream_run_1",
    "stream_run_2_3",
    "stream_run_4_15",
    "stream_run_16_plus",
    "ras_underflow",
    "ras_overflow",
    "btb_alloc",
    "btb_evict",
    "ic_refill_latency_bin_0",
    "ic_refill_latency_bin_1",
    "ic_refill_latency_bin_2",
    "ic_refill_latency_bin_3",
    "ic_refill_latency_bin_4",
    "ic_refill_latency_bin_5",
    "ic_refill_latency_bin_6",
    "ic_refill_latency_bin_7",
    "next_line_gap_bin_0",
    "next_line_gap_bin_1",
    "next_line_gap_bin_2",
    "next_line_gap_bin_3",
    "next_line_gap_bin_4",
    "next_line_gap_bin_5",
    "next_line_gap_bin_6",
    "next_line_gap_bin_7",
    "recovery_latency_bin_0",
    "recovery_latency_bin_1",
    "recovery_latency_bin_2",
    "recovery_latency_bin_3",
    "recovery_latency_bin_4",
    "recovery_latency_bin_5",
    "recovery_latency_bin_6",
    "recovery_latency_bin_7",
    "ic_set_miss_0",
    "ic_set_miss_1",
    "ic_set_miss_2",
    "ic_set_miss_3",
    "ic_set_miss_4",
    "ic_set_miss_5",
    "ic_set_miss_6",
    "ic_set_miss_7",
    "ic_set_miss_8",
    "ic_set_miss_9",
    "ic_set_miss_10",
    "ic_set_miss_11",
    "ic_set_miss_12",
    "ic_set_miss_13",
    "ic_set_miss_14",
    "ic_set_miss_15",
    "ic_set_miss_16",
    "ic_set_miss_17",
    "ic_set_miss_18",
    "ic_set_miss_19",
    "ic_set_miss_20",
    "ic_set_miss_21",
    "ic_set_miss_22",
    "ic_set_miss_23",
    "ic_set_miss_24",
    "ic_set_miss_25",
    "ic_set_miss_26",
    "ic_set_miss_27",
    "ic_set_miss_28",
    "ic_set_miss_29",
    "ic_set_miss_30",
    "ic_set_miss_31",
    "ic_set_miss_32",
    "ic_set_miss_33",
    "ic_set_miss_34",
    "ic_set_miss_35",
    "ic_set_miss_36",
    "ic_set_miss_37",
    "ic_set_miss_38",
    "ic_set_miss_39",
    "ic_set_miss_40",
    "ic_set_miss_41",
    "ic_set_miss_42",
    "ic_set_miss_43",
    "ic_set_miss_44",
    "ic_set_miss_45",
    "ic_set_miss_46",
    "ic_set_miss_47",
    "ic_set_miss_48",
    "ic_set_miss_49",
    "ic_set_miss_50",
    "ic_set_miss_51",
    "ic_set_miss_52",
    "ic_set_miss_53",
    "ic_set_miss_54",
    "ic_set_miss_55",
    "ic_set_miss_56",
    "ic_set_miss_57",
    "ic_set_miss_58",
    "ic_set_miss_59",
    "ic_set_miss_60",
    "ic_set_miss_61",
    "ic_set_miss_62",
    "ic_set_miss_63",
    "ic_set_miss_64",
    "ic_set_miss_65",
    "ic_set_miss_66",
    "ic_set_miss_67",
    "ic_set_miss_68",
    "ic_set_miss_69",
    "ic_set_miss_70",
    "ic_set_miss_71",
    "ic_set_miss_72",
    "ic_set_miss_73",
    "ic_set_miss_74",
    "ic_set_miss_75",
    "ic_set_miss_76",
    "ic_set_miss_77",
    "ic_set_miss_78",
    "ic_set_miss_79",
    "ic_set_miss_80",
    "ic_set_miss_81",
    "ic_set_miss_82",
    "ic_set_miss_83",
    "ic_set_miss_84",
    "ic_set_miss_85",
    "ic_set_miss_86",
    "ic_set_miss_87",
    "ic_set_miss_88",
    "ic_set_miss_89",
    "ic_set_miss_90",
    "ic_set_miss_91",
    "ic_set_miss_92",
    "ic_set_miss_93",
    "ic_set_miss_94",
    "ic_set_miss_95",
    "ic_set_miss_96",
    "ic_set_miss_97",
    "ic_set_miss_98",
    "ic_set_miss_99",
    "ic_set_miss_100",
    "ic_set_miss_101",
    "ic_set_miss_102",
    "ic_set_miss_103",
    "ic_set_miss_104",
    "ic_set_miss_105",
    "ic_set_miss_106",
    "ic_set_miss_107",
    "ic_set_miss_108",
    "ic_set_miss_109",
    "ic_set_miss_110",
    "ic_set_miss_111",
    "ic_set_miss_112",
    "ic_set_miss_113",
    "ic_set_miss_114",
    "ic_set_miss_115",
    "ic_set_miss_116",
    "ic_set_miss_117",
    "ic_set_miss_118",
    "ic_set_miss_119",
    "ic_set_miss_120",
    "ic_set_miss_121",
    "ic_set_miss_122",
    "ic_set_miss_123",
    "ic_set_miss_124",
    "ic_set_miss_125",
    "ic_set_miss_126",
    "ic_set_miss_127",
    "ic_set_miss_overflow",
    "f2_recovery_cycles", "f2_recovery_completed", "f2_recovery_latency",
    "f2_recovery_latency_bin_0", "f2_recovery_latency_bin_1", "f2_recovery_latency_bin_2", "f2_recovery_latency_bin_3",
    "f2_recovery_latency_bin_4", "f2_recovery_latency_bin_5", "f2_recovery_latency_bin_6", "f2_recovery_latency_bin_7",
    "cpu_cycles", "retired_instructions")
  val count = names.size
  val rawBase = 0x1000
  def index(name: String): Int = { val i = names.indexOf(name); require(i >= 0, name); i }
  def raw(name: String): Int = rawBase + index(name)
  def bin(value: UInt): UInt = MuxCase(7.U(3.W), (0 until 7).map(i =>
    (value < (BigInt(1) << (i + 3)).U) -> i.U(3.W)))
}
class BreezeObserveEvents extends Bundle {
  val delta = Vec(BreezeObserveEvent.count, UInt(32.W))
  def apply(name: String): UInt = delta(BreezeObserveEvent.index(name))
}
class BreezeObserveTrace extends Bundle {
  val cycle = UInt(64.W)
  val pc = UInt(64.W)
  val vaddr = UInt(64.W)
  val paddr = UInt(64.W)
  val satp = UInt(64.W)
  // bit0 miss, bit1 retry, bits3:2 privilege; remaining bits reserved.
  val flags = UInt(64.W)
}
/** M-mode indexed readout; no request, ready, kill, or architectural state is driven. */
class BreezeObserveBank extends Module {
  val io = IO(new Bundle {
    val events = Input(new BreezeObserveEvents)
    val trace = Flipped(Valid(new BreezeObserveTrace))
    val write = Input(Bool()); val address = Input(UInt(12.W)); val data = Input(UInt(64.W))
    val control = Output(UInt(64.W)); val index = Output(UInt(64.W)); val read = Output(UInt(64.W))
    val traceIndex = Output(UInt(64.W)); val traceRead = Output(UInt(64.W)); val traceStatus = Output(UInt(64.W))
  })
  val frozen = RegInit(false.B)
  val enabled = RegInit(false.B)
  val period = RegInit(0.U(16.W))
  val index = RegInit(0.U(16.W)); val traceIndex = RegInit(0.U(8.W))
  val counts = RegInit(VecInit(Seq.fill(BreezeObserveEvent.count)(0.U(48.W))))
  val samples = Reg(Vec(16, new BreezeObserveTrace))
  val used = RegInit(0.U(5.W)); val dropped = RegInit(0.U(32.W)); val skip = RegInit(0.U(16.W))
  val controlWrite = io.write && io.address === 0x7d0.U
  val clear = controlWrite && io.data(1)
  when(controlWrite) { frozen := io.data(0); enabled := io.data(2); period := io.data(31,16) }
  when(io.write && io.address === 0x7d1.U) { index := io.data(15,0) }
  when(io.write && io.address === 0x7d3.U) { traceIndex := io.data(7,0) }
  for (i <- 0 until BreezeObserveEvent.count) {
    when(clear) { counts(i) := 0.U }
      .elsewhen(!frozen && !controlWrite) { counts(i) := counts(i) + io.events.delta(i) }
  }
  when(!frozen && !controlWrite && enabled && io.trace.valid) {
    when(skip === 0.U) {
      skip := period
      when(used < 16.U) { samples(used(3,0)) := io.trace.bits; used := used + 1.U }
        .otherwise { dropped := Mux(dropped.andR, dropped, dropped + 1.U) }
    }.otherwise { skip := skip - 1.U }
  }
  when(clear) { used := 0.U; dropped := 0.U; skip := 0.U }
  io.control := (period.pad(64) << 16) | (enabled.asUInt << 2) | frozen.asUInt
  io.index := index
  io.read := Mux(index < BreezeObserveEvent.count.U, counts(index(log2Ceil(BreezeObserveEvent.count)-1,0)), 0.U)
  io.traceIndex := traceIndex
  val entry = traceIndex(7,3); val word = traceIndex(2,0)
  val selected = samples(entry(3,0))
  val words = VecInit(Seq(selected.cycle, selected.pc, selected.vaddr, selected.paddr, selected.satp, selected.flags))
  io.traceRead := Mux(entry < used && word < 6.U, words(word), 0.U)
  io.traceStatus := (dropped.pad(64) << 32) | used
}

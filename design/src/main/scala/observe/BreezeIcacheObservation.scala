package flow.observe

import chisel3._
import chisel3.util._
import flow.config.BreezeMemGeometry

/** One occurrence-time packet. Data and qualifications cross the same edge;
  * late frontend cancellation never enables a wide observer register bank.
  */
class BreezeIcacheObserveSample(g: BreezeMemGeometry) extends Bundle {
  private val lineBits = g.paddrBits - log2Ceil(g.lineBytes)
  val demand = Valid(new Bundle { val line = UInt(lineBits.W); val miss = Bool() })
  val install = Valid(new Bundle {
    val line = UInt(lineBits.W); val way = UInt(log2Ceil(g.l1iWays).max(1).W)
    val evicted = Bool(); val victim = UInt(lineBits.W)
  })
  val fence = Bool()
  val lookupRetry = Bool(); val tlbRetry = Bool()
  val refillStart = Bool(); val refillWaiting = Bool(); val refillReturned = Bool()
  val refillCancelled = Bool(); val refillError = Bool()
  val trace = new BreezeObserveTrace
}

/** Passive observation pipeline; it has no functional cache control outputs. */
class BreezeIcacheObservation(g: BreezeMemGeometry) extends Module {
  val io = IO(new Bundle {
    val sample = Input(new BreezeIcacheObserveSample(g))
    val events = Output(new BreezeObserveEvents)
    val trace = Valid(new BreezeObserveTrace)
  })
  val sample = RegNext(io.sample, 0.U.asTypeOf(io.sample))
  io.events := 0.U.asTypeOf(io.events)
  io.events("ic_demand_access") := sample.demand.valid.asUInt
  io.events("ic_demand_miss") := (sample.demand.valid && sample.demand.bits.miss).asUInt
  io.events("ic_lookup_retry") := sample.lookupRetry.asUInt
  io.events("itlb_retry") := sample.tlbRetry.asUInt
  val refillAge = RegInit(0.U(32.W))
  when(sample.refillWaiting) { refillAge := Mux(refillAge.andR, refillAge, refillAge + 1.U) }
  when(sample.refillStart) { refillAge := 0.U }
  val latency = Mux(refillAge.andR, refillAge, refillAge + 1.U)
  io.events("ic_refill_completed") := sample.refillReturned.asUInt
  io.events("ic_refill_latency") := Mux(sample.refillReturned, latency, 0.U)
  io.events("ic_cancelled_refill") := (sample.refillReturned && sample.refillCancelled).asUInt
  io.events("ic_refill_error") := (sample.refillReturned && sample.refillError).asUInt
  io.events("ic_refill_wait") := sample.refillWaiting.asUInt
  io.events("fence_i") := sample.fence.asUInt
  io.events("ic_valid_eviction") := (sample.install.valid && sample.install.bits.evicted).asUInt
  for(i <- 0 until 8) io.events(s"ic_refill_latency_bin_$i") :=
    (sample.refillReturned && BreezeObserveEvent.bin(latency) === i.U).asUInt

  val locality = Module(new BreezeInstructionLocality(g))
  locality.io.demand := sample.demand
  locality.io.install := sample.install
  locality.io.fence := sample.fence
  for(name <- Seq("new_line", "sequential_line", "nonsequential_line", "next_line_resident",
      "next_line_nonresident", "next_line_unknown", "recent_eviction_hit_4", "recent_eviction_hit_8",
      "recent_eviction_hit_16", "stream_run_1", "stream_run_2_3", "stream_run_4_15", "stream_run_16_plus",
      "ic_set_miss_overflow") ++ (0 until 8).map(i => s"next_line_gap_bin_$i") ++
      (0 until 128).map(i => s"ic_set_miss_$i")) {
    io.events(name) := locality.io.observation(name)
  }
  val lastLine = Reg(UInt((g.paddrBits - log2Ceil(g.lineBytes)).W))
  val lastValid = RegInit(false.B)
  io.trace.valid := sample.demand.valid &&
    (!lastValid || lastLine =/= sample.demand.bits.line || sample.demand.bits.miss)
  io.trace.bits := sample.trace
  when(sample.demand.valid) { lastLine := sample.demand.bits.line; lastValid := true.B }
  when(sample.fence) { lastValid := false.B }
}

class BreezeFrontendRecoverySample extends Bundle {
  val resolvedRedirect = Bool(); val globalCancel = Bool(); val olderCancel = Bool()
  val instructionValid = Bool()
  val f2Redirect = Bool(); val hasFault = Bool(); val actualNext = UInt(64.W)
  val f1Valid = Bool(); val f1Pc = UInt(64.W)
}

/** Recovery metrics process captured controls and payloads together. */
class BreezeFrontendRecoveryObservation extends Module {
  val io = IO(new Bundle {
    val sample = Input(new BreezeFrontendRecoverySample)
    val events = Output(new BreezeObserveEvents)
  })
  val sample = RegNext(io.sample, 0.U.asTypeOf(io.sample))
  io.events := 0.U.asTypeOf(io.events)
  val recovery = RegInit(false.B); val age = RegInit(0.U(32.W))
  val done = recovery && sample.instructionValid && !sample.olderCancel
  val latency = Mux(age.andR, age, age + 1.U)
  when(recovery) { age := Mux(age.andR, age, age + 1.U) }
  when(done || sample.globalCancel) { recovery := false.B }
  when(sample.resolvedRedirect && !sample.globalCancel) { recovery := true.B; age := 0.U }
  io.events("recovery_cycles") := recovery.asUInt
  io.events("recovery_completed") := done.asUInt
  io.events("recovery_latency") := Mux(done, latency, 0.U)
  io.events("recovery_superseded") := (recovery && (sample.resolvedRedirect || sample.globalCancel)).asUInt
  for(i <- 0 until 8) io.events(s"recovery_latency_bin_$i") := (done && BreezeObserveEvent.bin(latency) === i.U).asUInt

  val f2Recovery = RegInit(false.B); val f2Age = RegInit(0.U(32.W))
  val target = Reg(UInt(64.W))
  val f2Done = f2Recovery && sample.f1Valid && sample.f1Pc === target && !sample.olderCancel
  val f2Latency = Mux(f2Age.andR, f2Age, f2Age + 1.U)
  when(f2Recovery) { f2Age := Mux(f2Age.andR, f2Age, f2Age + 1.U) }
  when(f2Done || sample.olderCancel) { f2Recovery := false.B }
  when(sample.f2Redirect && !sample.hasFault && !sample.olderCancel) {
    f2Recovery := true.B; f2Age := 0.U; target := sample.actualNext
  }
  io.events("f2_recovery_cycles") := f2Recovery.asUInt
  io.events("f2_recovery_completed") := f2Done.asUInt
  io.events("f2_recovery_latency") := Mux(f2Done, f2Latency, 0.U)
  for(i <- 0 until 8) io.events(s"f2_recovery_latency_bin_$i") :=
    (f2Done && BreezeObserveEvent.bin(f2Latency) === i.U).asUInt
  io.events("fe_wait_recovery") := (recovery || f2Recovery).asUInt
}

package flow.observe

import chisel3._
import chisel3.util._
import flow.config.BreezeMemGeometry

/** Passive address-only observer. Never drives a cache read, refill, or replacement.
  * Resident tags use a separate synchronous mirror. Same-set read/write and
  * fence collisions are explicitly UNKNOWN rather than a guessed hit/miss.
  * Eviction windows measure recently displaced lines, not a functional victim cache.
  */
class BreezeInstructionLocality(g: BreezeMemGeometry) extends Module {
  private val lineBits = g.paddrBits - log2Ceil(g.lineBytes)
  private val setBits = log2Ceil(g.l1Sets)
  private val wayBits = log2Ceil(g.l1iWays).max(1)
  def set(line: UInt): UInt = if(setBits == 0) 0.U else line(setBits-1,0)
  val io = IO(new Bundle {
    val demand = Flipped(Valid(new Bundle { val line = UInt(lineBits.W); val miss = Bool() }))
    val install = Flipped(Valid(new Bundle {
      val line = UInt(lineBits.W); val way = UInt(wayBits.W)
      val evicted = Bool(); val victim = UInt(lineBits.W)
    }))
    val fence = Input(Bool())
    val observation = Output(new BreezeObserveEvents)
  })
  io.observation := 0.U.asTypeOf(io.observation)
  val clockCount = RegInit(0.U(32.W)); clockCount := clockCount + 1.U
  val previous = Reg(UInt(lineBits.W)); val previousValid = RegInit(false.B)
  val previousTime = Reg(UInt(32.W)); val run = RegInit(0.U(32.W))
  val changed = io.demand.valid && (!previousValid || previous =/= io.demand.bits.line)
  val sequential = changed && previousValid && !previous.andR && io.demand.bits.line === previous + 1.U
  val discontinuity = changed && previousValid && !sequential
  val gap = clockCount - previousTime
  io.observation("new_line") := changed.asUInt
  io.observation("sequential_line") := sequential.asUInt
  io.observation("nonsequential_line") := discontinuity.asUInt
  for(i <- 0 until 8) io.observation(s"next_line_gap_bin_$i") := (sequential && BreezeObserveEvent.bin(gap) === i.U).asUInt
  val endRun = (discontinuity || io.fence) && previousValid
  io.observation("stream_run_1") := (endRun && run === 1.U).asUInt
  io.observation("stream_run_2_3") := (endRun && run >= 2.U && run < 4.U).asUInt
  io.observation("stream_run_4_15") := (endRun && run >= 4.U && run < 16.U).asUInt
  io.observation("stream_run_16_plus") := (endRun && run >= 16.U).asUInt
  when(changed) {
    previous := io.demand.bits.line; previousValid := true.B; previousTime := clockCount
    run := Mux(sequential, Mux(run.andR, run, run + 1.U), 1.U)
  }
  when(io.fence) { previousValid := false.B; run := 0.U }

  val resident = SyncReadMem(g.l1Sets, Vec(g.l1iWays, UInt(lineBits.W)))
  val valid = RegInit(VecInit(Seq.fill(g.l1Sets)(0.U(g.l1iWays.W))))
  val next = io.demand.bits.line + 1.U
  val read = changed && !io.demand.bits.line.andR && !io.fence
  val data = resident.read(set(next), read)
  val query = RegEnable(next, read)
  val mask = RegEnable(valid(set(next)), read)
  val collision = io.install.valid && set(io.install.bits.line) === set(next)
  val uncertain = RegNext(read && collision, false.B) || io.fence
  val returned = RegNext(read, false.B)
  val hit = VecInit((0 until g.l1iWays).map(w => mask(w) && data(w) === query)).asUInt.orR
  io.observation("next_line_unknown") := (returned && uncertain).asUInt
  io.observation("next_line_resident") := (returned && !uncertain && hit).asUInt
  io.observation("next_line_nonresident") := (returned && !uncertain && !hit).asUInt
  when(io.install.valid && !io.fence) {
    val enable = UIntToOH(io.install.bits.way, g.l1iWays).asBools
    resident.write(set(io.install.bits.line), VecInit(Seq.fill(g.l1iWays)(io.install.bits.line)), enable)
    valid(set(io.install.bits.line)) := valid(set(io.install.bits.line)) | UIntToOH(io.install.bits.way, g.l1iWays)
  }
  when(io.fence) { valid := VecInit(Seq.fill(g.l1Sets)(0.U(g.l1iWays.W))) }

  val ghost = Reg(Vec(16, UInt(lineBits.W)))
  val ghostValid = RegInit(VecInit(Seq.fill(16)(false.B)))
  val matches = VecInit((0 until 16).map(i => ghostValid(i) && ghost(i) === io.demand.bits.line))
  for(n <- Seq(4,8,16)) io.observation(s"recent_eviction_hit_$n") :=
    (io.demand.valid && io.demand.bits.miss && matches.take(n).reduce(_ || _) && !io.fence).asUInt
  // Shift only at a real installation which overwrites a valid physical line.
  // Remove any line restored by an installation, including on the shift edge.
  when(io.install.valid && !io.fence) {
    for(i <- 0 until 16) {
      val shift = io.install.bits.evicted
      val candidate = if(i==0) Mux(shift, io.install.bits.victim, ghost(i)) else Mux(shift, ghost(i-1), ghost(i))
      val isValid = if(i==0) Mux(shift, true.B, ghostValid(i)) else Mux(shift, ghostValid(i-1), ghostValid(i))
      ghost(i) := (if(i==0) Mux(shift, candidate, ghost(i)) else candidate)
      ghostValid(i) := isValid && candidate =/= io.install.bits.line
    }
  }
  when(io.fence) { ghostValid := VecInit(Seq.fill(16)(false.B)) }
  for(i <- 0 until 128) io.observation(s"ic_set_miss_$i") :=
    (io.demand.valid && io.demand.bits.miss && set(io.demand.bits.line) === i.U && !io.fence).asUInt
  io.observation("ic_set_miss_overflow") :=
    (io.demand.valid && io.demand.bits.miss && set(io.demand.bits.line) >= 128.U && !io.fence).asUInt
}

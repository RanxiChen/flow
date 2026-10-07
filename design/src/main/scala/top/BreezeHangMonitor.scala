package flow.top

import chisel3._
import chisel3.util._

/** Passive observer: there are no bus outputs and no feedback into the DUT.
  * Reasons: bit 0 retirement; bits 1..10 mem/mmio AR,R,AW,W,B stalls;
  * bits 11..14 oldest outstanding mem read/write, mmio read/write timeout.
  * Outstanding read ages are retained across completions, including the age
  * of the second request, so progress cannot hide an older timed-out request.
  */
class BreezeHangMonitor(threshold: Int = 10000000, readSlots: Int = 2) extends Module {
  require(threshold > 0 && readSlots > 0)
  private val ageBits = math.max(1, log2Ceil(threshold + 1))
  val io = IO(new Bundle {
    val retire = Input(Bool())
    val stalled = Input(Vec(10, Bool()))
    val request = Input(Vec(4, Bool()))
    val complete = Input(Vec(4, Bool()))
    val noRetireCycles = Output(UInt(32.W))
    val reasons = Output(UInt(15.W))
    val hang = Output(Bool())
  })
  val idle = RegInit(0.U(32.W))
  when(io.retire) { idle := 0.U }
    .elsewhen(!idle.andR) { idle := idle + 1.U }
  io.noRetireCycles := idle
  val conditions = Wire(Vec(15, Bool()))
  conditions(0) := !io.retire && idle >= (threshold - 1).U
  for (i <- 0 until 10) {
    val age = RegInit(0.U(ageBits.W))
    when(!io.stalled(i)) { age := 0.U }
      .elsewhen(age < threshold.U) { age := age + 1.U }
    conditions(i + 1) := io.stalled(i) && age >= (threshold - 1).U
  }
  for ((depth, i) <- Seq(readSlots, 1, 1, 1).zipWithIndex) {
    val count = RegInit(0.U(log2Ceil(depth + 1).W))
    val ages = RegInit(VecInit(Seq.fill(depth)(0.U(ageBits.W))))
    val pop = io.complete(i) && count =/= 0.U
    // A zero-latency response to a new request does not occupy a slot.
    val push = io.request(i) && !(io.complete(i) && count === 0.U)
    for (j <- 0 until depth) {
      val source = if (j + 1 < depth) Mux(pop, ages(j + 1), ages(j)) else ages(j)
      ages(j) := Mux(source < threshold.U, source + 1.U, source)
      when(push && (count - pop.asUInt) === j.U) { ages(j) := 0.U }
    }
    when(push =/= pop) { count := count + push.asUInt - pop.asUInt }
    conditions(11 + i) := count =/= 0.U && ages(0) >= (threshold - 1).U
  }
  val reasons = RegInit(0.U(15.W))
  reasons := reasons | conditions.asUInt
  io.reasons := reasons
  io.hang := reasons.orR
}

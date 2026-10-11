package flow.backend

import chisel3._
import chisel3.util._
import flow.interface.L1DDestination

/** Ownership boundary shared by arithmetic and coprocessor adapters.
  * reserve is ID acceptance; available is registered capacity. Tokens are
  * registered at the caller. Results retain identity/data until grant.
  */
class LongLatResult extends Bundle {
  val rd = new L1DDestination
  val data = UInt(64.W)
  val flags = UInt(5.W)
}
class LongLatUnit extends Bundle {
  val reserve = Bool()
  val available = Bool()
  val commitD = Bool()
  val killD = Bool()
  val returned = UInt(6.W)
  val drained = Bool()
  val result = Decoupled(new FpResult)
}
class CoprocessorDecision extends Bundle {
  val exception = Bool()
  val cause = UInt(64.W)
  val tval = UInt(64.W)
  val vstartMarker = Bool()
}

/** BE2 accelerated stub: one reserved custom-0 request, random registered
  * decision latency, Ok/Exc. Scalar results/Serial are deferred to R05'.
  * No architectural side effects exist before or after authorization.
  */
class CoprocessorStub(seed: Int) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Valid(UInt(32.W)))
    val commitD = Input(Bool())
    val killD = Input(Bool())
    val decision = Valid(new CoprocessorDecision)
    val returned = Output(Bool())
    val drained = Output(Bool())
  })
  val occupied = RegInit(false.B)
  val known = RegInit(false.B)
  val inst = Reg(UInt(32.W))
  val random = RegInit((BigInt(seed & 0xffff) | 1).U(16.W))
  val delay = Reg(UInt(4.W))
  io.decision.valid := occupied && known && !io.killD
  io.decision.bits.exception := inst(12)
  io.decision.bits.cause := 2.U
  io.decision.bits.tval := inst
  io.decision.bits.vstartMarker := inst(12)
  io.returned := occupied && (io.commitD || io.killD)
  io.drained := !occupied
  when(io.req.valid) {
    assert(!occupied, "[copro R3] reserved request rejected")
    occupied := true.B; known := false.B; inst := io.req.bits
    delay := random(3,0)
    random := Cat(random(14,0), random(15) ^ random(13) ^ random(12) ^ random(10))
  }
  when(occupied && !known) {
    when(delay === 0.U) { known := true.B }.otherwise { delay := delay - 1.U }
  }
  when(io.commitD || io.killD) { occupied := false.B; known := false.B }
  when(io.commitD) {
    assert(occupied && known && !inst(12), "[copro] authorization without Ok")
  }
  assert(!(io.killD && io.req.valid), "[copro R2] request during delayed kill")
}

package flow.frontend

import chisel3._
import chisel3.util._
import flow.interface._

/** The slow redirect ends at D; frontend controls see only its registered copy.
  * Two elastic slots absorb ID backpressure without a combinational ready path.
  */
class BreezeFrontendBoundary(val ghrLength: Int = 0) extends Module {
  val io = IO(new Bundle {
    val fast = Input(new FrontendRedirectIO(64))
    val slow = Input(new FrontendRedirectIO(64))
    val applied = Output(new FrontendRedirectIO(64))
    val in = Flipped(Decoupled(new FrontendFetchBundle(64, ghrLength)))
    val out = Decoupled(new FrontendFetchBundle(64, ghrLength))
  })
  val slowRedirect = RegNext(io.slow, 0.U.asTypeOf(io.slow))
  io.applied := io.fast
  io.applied.valid := slowRedirect.valid || io.fast.valid
  io.applied.target := Mux(slowRedirect.valid, slowRedirect.target, io.fast.target)
  io.applied.flush := slowRedirect.flush || io.fast.flush
  io.applied.cacheFlush := slowRedirect.cacheFlush
  // Always capture an admitted frontend item, including an empty bypass.
  // Queue(flow=true) suppresses its RAM write when the bypass is consumed;
  // that puts backend ready (and thus S2 hold) on the storage write enable.
  // Here only registered occupancy and the frontend input drive that enable.
  val slots = Reg(Vec(2, new FrontendFetchBundle(64, ghrLength)))
  val readIndex = RegInit(false.B)
  val writeIndex = RegInit(false.B)
  val count = RegInit(0.U(2.W))
  val empty = count === 0.U
  io.in.ready := count =/= 2.U
  io.out.valid := !empty || io.in.valid
  io.out.bits := Mux(empty, io.in.bits, slots(readIndex.asUInt))
  val enqueue = io.in.fire
  val dequeue = io.out.fire
  // Clear at the kill edge and at its delayed frontend flush edge. The latter
  // removes any request that was accepted during the redirect gap.
  val clear = io.fast.flush || io.slow.flush || slowRedirect.flush
  when(enqueue) {
    slots(writeIndex.asUInt) := io.in.bits
  }
  writeIndex := !clear && (writeIndex ^ enqueue)
  readIndex := !clear && (readIndex ^ dequeue)
  count := Mux(clear, 0.U, count + enqueue.asUInt - dequeue.asUInt)
  assert(count <= 2.U)
}

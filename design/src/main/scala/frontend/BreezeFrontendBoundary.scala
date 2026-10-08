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
  // With pipe=false, enqueue ready depends solely on registered occupancy.
  // flow=true bypasses the empty buffer without changing the ID->EX contract.
  val skid = Module(new Queue(new FrontendFetchBundle(64, ghrLength), 2,
    pipe = false, flow = true, hasFlush = true))
  skid.io.enq <> io.in
  io.out <> skid.io.deq
  // Clear at the kill edge and at its delayed frontend flush edge. The latter
  // removes any request that was accepted during the redirect gap.
  skid.io.flush.get := io.fast.flush || io.slow.flush || slowRedirect.flush
}

package flow.rvv.zve

import chisel3._
import chisel3.util._

/** Tagged lane services may return in any order and have independent ready.
  * An accepted batch owns its result storage until output.fire. Inactive lanes
  * are never sent to an FPU and therefore cannot generate fflags. */
class ZveFpBatchPipeline(lanes: Int, depth: Int = 8, metadataBits: Int = 32) extends Module {
  require(isPow2(lanes) && isPow2(depth) && depth >= 2)
  private val slotBits = log2Ceil(depth)
  private val laneBits = log2Ceil(lanes)
  require(slotBits + laneBits <= 16)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ZveFpBatch(lanes, metadataBits)))
    val out = Decoupled(new ZveFpBatchResult(lanes, metadataBits))
    val fp = Vec(lanes, new ZveFpService)
    val drained = Output(Bool())
  })
  val live = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val batches = Reg(Vec(depth, new ZveFpBatch(lanes, metadataBits)))
  val done = Reg(Vec(depth, UInt(lanes.W)))
  val requested = Reg(Vec(depth, UInt(lanes.W)))
  val data = Reg(Vec(depth, Vec(lanes, UInt(64.W))))
  val flags = Reg(Vec(depth, Vec(lanes, UInt(5.W))))
  val allocate = RegInit(0.U(slotBits.W))
  val launch = Module(new Queue(UInt(slotBits.W), depth, pipe=false, flow=false))
  val order = Module(new Queue(UInt(slotBits.W), depth, pipe=false, flow=false))
  io.in.ready := !live(allocate) && launch.io.enq.ready && order.io.enq.ready
  launch.io.enq.valid := io.in.fire; launch.io.enq.bits := allocate
  order.io.enq.valid := io.in.fire; order.io.enq.bits := allocate
  when(io.in.fire) {
    live(allocate) := true.B; batches(allocate) := io.in.bits
    done(allocate) := ~io.in.bits.active
    requested(allocate) := 0.U
    flags(allocate).foreach(_ := 0.U)
    data(allocate).foreach(_ := 0.U)
    allocate := allocate + 1.U
  }
  val sent = RegInit(0.U(lanes.W))
  val selected = launch.io.deq.bits
  val request = batches(selected)
  val fires = Wire(Vec(lanes, Bool()))
  for(l <- 0 until lanes) {
    val port = io.fp(l)
    port.request.valid := launch.io.deq.valid && request.active(l) && !sent(l)
    port.request.bits.tag := ((selected << laneBits) | l.U).pad(16)
    port.request.bits.a := request.a(l); port.request.bits.b := request.b(l); port.request.bits.c := request.c(l)
    port.request.bits.operation := request.operation; port.request.bits.modifier := request.modifier
    port.request.bits.rounding := request.rounding
    port.request.bits.sourceFormat := request.sourceFormat; port.request.bits.destinationFormat := request.destinationFormat
    port.request.bits.integerFormat := request.integerFormat
    fires(l) := port.request.fire
    // Storage was reserved on batch acceptance, so responses need not wait for
    // downstream VRF or another lane. Tags are checked before every update.
    port.response.ready := true.B
    val tagSlot = (port.response.bits.tag >> laneBits)(slotBits-1,0)
    when(port.response.fire) {
      assert(live(tagSlot) && batches(tagSlot).active(l) && !done(tagSlot)(l), "unowned or duplicate FP response")
      assert(requested(tagSlot)(l) || (port.request.fire && tagSlot === selected), "FP returned before request acceptance")
      assert(port.response.bits.tag === ((tagSlot << laneBits) | l.U), "FP response lane/tag mismatch")
      data(tagSlot)(l) := port.response.bits.data; flags(tagSlot)(l) := port.response.bits.flags
    }
  }
  // Multiple lanes returning to the same slot must set their bits together.
  for(s <- 0 until depth) {
    val received = VecInit((0 until lanes).map(l => io.fp(l).response.fire &&
      (io.fp(l).response.bits.tag >> laneBits) === s.U)).asUInt
    when(received.orR) { done(s) := done(s) | received }
    when(launch.io.deq.valid && selected === s.U && fires.asUInt.orR) {
      requested(s) := requested(s) | fires.asUInt
    }
  }
  val allSent = (sent | fires.asUInt | ~request.active).andR
  launch.io.deq.ready := allSent
  when(launch.io.deq.fire) { sent := 0.U }
    .elsewhen(fires.asUInt.orR) { sent := sent | fires.asUInt }
  val outputSlot = order.io.deq.bits
  io.out.valid := order.io.deq.valid && done(outputSlot).andR
  io.out.bits.metadata := batches(outputSlot).metadata
  io.out.bits.active := batches(outputSlot).active
  io.out.bits.data := data(outputSlot)
  io.out.bits.flags := flags(outputSlot).reduce(_ | _)
  order.io.deq.ready := io.out.ready && done(outputSlot).andR
  when(io.out.fire) { live(outputSlot) := false.B }
  io.drained := !live.asUInt.orR && !launch.io.deq.valid && !order.io.deq.valid
}

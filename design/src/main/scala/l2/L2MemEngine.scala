package flow.l2

import chisel3._
import chisel3.util._
import flow.bus.{Axi4, Axi4MasterIO, Axi4Params}
import flow.coherence._

/** AXI4 memory engine: per-slot line reads, one-entry write buffer (coherence-l2-rtl-spec §7). */
class L2MemEngine(p: CoherenceParams) extends Module {
  val axiP = Axi4Params(p.paddrBits, p.memDataBits, p.slotBits)
  val io = IO(new Bundle {
    val readReq = Flipped(Decoupled(new Bundle {
      val slot = UInt(p.slotBits.W)
      val addr = UInt(p.lineAddrBits.W)
    }))
    val readDone = Valid(new Bundle {
      val slot = UInt(p.slotBits.W)
      val data = UInt(p.lineBits.W)
      val error = Bool()
    })
    /** EVICT task hands {addr, data} to the write buffer in S2. */
    val wbFree = Output(Bool())
    val wbPush = Flipped(Valid(new Bundle {
      val addr = UInt(p.lineAddrBits.W)
      val data = UInt(p.lineBits.W)
    }))
    val writeErrors = Output(UInt(8.W))
    val mem = new Axi4MasterIO(axiP)
  })

  // ---------------- Read: AR, in-order R routing ----------------
  val order = Module(new Queue(UInt(p.slotBits.W), p.l2Slots))
  val wbValid = RegInit(false.B)
  val wbAddr = Reg(UInt(p.lineAddrBits.W))
  val wbData = Reg(UInt(p.lineBits.W))
  // Reserve the response owner at local acceptance, independently of the
  // shared AXI AR ready tree. AR is driven only by a registered dispatch head.
  val dispatch = Module(new Queue(chiselTypeOf(io.readReq.bits), p.l2Slots,
    pipe = false, flow = false))
  val rawBlock = wbValid && wbAddr === io.readReq.bits.addr
  // A write accepted at this edge has priority. Once a read is queued, no
  // new write is accepted until its AR has left, so stalled AR remains stable.
  val admitRead = !rawBlock && !io.wbPush.valid
  io.readReq.ready := dispatch.io.enq.ready && order.io.enq.ready && admitRead
  dispatch.io.enq.valid := io.readReq.valid && order.io.enq.ready && admitRead
  dispatch.io.enq.bits := io.readReq.bits
  order.io.enq.valid := io.readReq.valid && dispatch.io.enq.ready && admitRead
  order.io.enq.bits := io.readReq.bits.slot
  io.mem.ar.valid := dispatch.io.deq.valid
  io.mem.ar.bits.id := dispatch.io.deq.bits.slot
  io.mem.ar.bits.addr := dispatch.io.deq.bits.addr ## 0.U(p.offBits.W)
  io.mem.ar.bits.len := (p.memBeats - 1).U
  io.mem.ar.bits.size := log2Ceil(p.memDataBits / 8).U
  io.mem.ar.bits.burst := Axi4.BurstIncr
  io.mem.ar.bits.prot := 0.U
  dispatch.io.deq.ready := io.mem.ar.ready
  val issuedReads = RegInit(0.U(log2Ceil(p.l2Slots + 1).W))
  val lineReturn = io.mem.r.fire && io.mem.r.bits.last
  when(io.mem.ar.fire =/= lineReturn) {
    issuedReads := Mux(io.mem.ar.fire, issuedReads + 1.U, issuedReads - 1.U)
  }
  when(!reset.asBool) {
    assert(io.readReq.fire === order.io.enq.fire &&
      io.readReq.fire === dispatch.io.enq.fire, "read reservation/dispatch mismatch")
    assert(order.io.count === dispatch.io.count +& issuedReads,
      "accepted read lost its dispatch or response owner")
    assert(!io.mem.r.fire || issuedReads =/= 0.U || io.mem.ar.fire,
      "read data returned before AR was issued")
    assert(!dispatch.io.deq.valid || !wbValid || wbAddr =/= dispatch.io.deq.bits.addr,
      "buffered write overtook an accepted read")
  }

  val rBeats = Reg(Vec(p.memBeats, UInt(p.memDataBits.W)))
  val rBeat = RegInit(0.U(log2Ceil(p.memBeats).W))
  val rErr = RegInit(false.B)
  io.mem.r.ready := true.B
  order.io.deq.ready := io.mem.r.fire && io.mem.r.bits.last
  when(io.mem.r.fire) {
    assert(order.io.deq.valid && io.mem.r.bits.id === order.io.deq.bits, "RID != in-flight queue head")
    assert(io.mem.r.bits.last === (rBeat === (p.memBeats - 1).U), "RLAST at the wrong line beat")
    rBeats(rBeat) := io.mem.r.bits.data
    rBeat := Mux(io.mem.r.bits.last, 0.U, rBeat + 1.U)
    rErr := Mux(io.mem.r.bits.last, false.B, rErr || io.mem.r.bits.resp =/= Axi4.RespOkay)
  }
  io.readDone.valid := io.mem.r.fire && io.mem.r.bits.last
  io.readDone.bits.slot := order.io.deq.bits
  io.readDone.bits.data := Cat(io.mem.r.bits.data, Cat(rBeats.init.reverse))
  io.readDone.bits.error := rErr || io.mem.r.bits.resp =/= Axi4.RespOkay

  // ---------------- Write: one buffer, AW + W beats, wait B ----------------
  val awSent = RegInit(false.B)
  val wBeat = RegInit(0.U(log2Ceil(p.memBeats + 1).W))
  val errCount = RegInit(0.U(8.W))
  io.wbFree := !wbValid && !dispatch.io.deq.valid
  when(io.wbPush.valid) {
    assert(io.wbFree, "write buffer pushed without admission")
    wbValid := true.B
    wbAddr := io.wbPush.bits.addr
    wbData := io.wbPush.bits.data
    awSent := false.B
    wBeat := 0.U
  }
  io.mem.aw.valid := wbValid && !awSent
  io.mem.aw.bits.id := 0.U
  io.mem.aw.bits.addr := wbAddr ## 0.U(p.offBits.W)
  io.mem.aw.bits.len := (p.memBeats - 1).U
  io.mem.aw.bits.size := log2Ceil(p.memDataBits / 8).U
  io.mem.aw.bits.burst := Axi4.BurstIncr
  io.mem.aw.bits.prot := 0.U
  when(io.mem.aw.fire) { awSent := true.B }
  io.mem.w.valid := wbValid && wBeat =/= p.memBeats.U
  io.mem.w.bits.data := wbData.asTypeOf(Vec(p.memBeats, UInt(p.memDataBits.W)))(wBeat(log2Ceil(p.memBeats) - 1, 0))
  io.mem.w.bits.strb := Fill(p.memDataBits / 8, 1.U(1.W))
  io.mem.w.bits.last := wBeat === (p.memBeats - 1).U
  when(io.mem.w.fire) { wBeat := wBeat + 1.U }
  io.mem.b.ready := wbValid && awSent && wBeat === p.memBeats.U
  when(io.mem.b.fire) {
    wbValid := false.B
    when(io.mem.b.bits.resp =/= Axi4.RespOkay) {
      errCount := errCount + 1.U
      printf(p"L2 writeback error at ${wbAddr}\n")
    }
  }
  io.writeErrors := errCount
}

package flow.l1i

import chisel3._
import chisel3.util._
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface.{L1CacheMissReqIO, L1CacheMissRespIO}

/** Refill client: independent demand (id=0) and prefetch (id=1) slots.
  * A demand pulse originates from an I-cache with one outstanding miss.
  * Coherence traffic remains stable under backpressure and is never canceled
  * after acceptance; the cache's flush tracking decides whether to install it.
  */
class L1IClient(g: BreezeMemGeometry) extends Module {
  val p = CoherenceParams(g)
  val io = IO(new Bundle {
    val demand = Flipped(new L1CacheMissReqIO(64))
    val demandRsp = Flipped(new L1CacheMissRespIO(p.lineBits))
    val prefetch = Flipped(Decoupled(UInt(64.W)))
    val prefetchRsp = Valid(new CoherenceRspDown(p))
    val coh = new ReadClientIO(p)
  })
  val pending = RegInit(VecInit(Seq.fill(2)(false.B)))
  val flight = RegInit(VecInit(Seq.fill(2)(false.B)))
  val addr = Reg(Vec(2, UInt(p.lineAddrBits.W)))
  val bad = RegInit(VecInit(Seq.fill(2)(false.B)))
  val sendValid = RegInit(false.B)
  val sendId = Reg(UInt(1.W))
  val sendAddr = Reg(UInt(p.lineAddrBits.W))
  val free = VecInit((0 until 2).map(i => !pending(i) && !flight(i) && !bad(i)))
  io.prefetch.ready := free(1)
  when(io.demand.req) {
    assert(free(0), "L1I demand pulse overwrote an outstanding refill")
    assert(io.demand.paddr(p.offBits-1,0) === 0.U, "L1I demand must be line aligned")
    addr(0) := io.demand.paddr(p.paddrBits-1,p.offBits)
    bad(0) := io.demand.paddr(63,p.paddrBits).orR
    pending(0) := !io.demand.paddr(63,p.paddrBits).orR
  }
  when(io.prefetch.fire) {
    addr(1) := io.prefetch.bits(p.paddrBits-1,p.offBits)
    bad(1) := io.prefetch.bits(63,p.paddrBits).orR
    pending(1) := !io.prefetch.bits(63,p.paddrBits).orR
  }
  when(!sendValid && pending.asUInt.orR) {
    sendValid := true.B
    val selected = Mux(pending(0), 0.U, 1.U)
    sendId := selected; sendAddr := addr(selected)
  }
  io.coh.req.valid := sendValid
  io.coh.req.bits := 0.U.asTypeOf(io.coh.req.bits)
  io.coh.req.bits.op := ReqOp.Read
  io.coh.req.bits.addr := sendAddr; io.coh.req.bits.id := sendId
  when(io.coh.req.fire) {
    pending(sendId) := false.B; flight(sendId) := true.B; sendValid := false.B
  }
  // Valid cache responses have no ready; the receiving cache owns its miss slot.
  io.coh.rspDown.ready := true.B
  val demandRsp = io.coh.rspDown.fire && io.coh.rspDown.bits.id === 0.U
  val prefetchRsp = io.coh.rspDown.fire && io.coh.rspDown.bits.id === 1.U
  // Each pulse/data/error crosses one registered boundary. Valid consumers
  // always consume at the next edge; the two IDs can return on adjacent cycles.
  // Never flush accepted responses here: the cache retains its miss owner and
  // uses flush tracking to suppress installation/delivery of an old refill.
  val demandReturn = demandRsp || bad(0)
  val demandData = RegEnable(Mux(bad(0), 0.U, io.coh.rspDown.bits.data), demandReturn)
  val demandError = RegEnable(bad(0) || io.coh.rspDown.bits.error, demandReturn)
  io.demandRsp.vld := RegNext(demandReturn, false.B)
  io.demandRsp.data := demandData
  io.demandRsp.error := demandError
  val prefetchReturn = prefetchRsp || bad(1)
  val prefetchData = Reg(new CoherenceRspDown(p))
  when(prefetchReturn) {
    prefetchData := io.coh.rspDown.bits
    when(bad(1)) {
      prefetchData := 0.U.asTypeOf(prefetchData)
      prefetchData.id := 1.U; prefetchData.op := RspDownOp.ReadData
      prefetchData.error := true.B
    }
  }
  io.prefetchRsp.valid := RegNext(prefetchReturn, false.B)
  io.prefetchRsp.bits := prefetchData
  bad := VecInit(Seq.fill(2)(false.B))
  // Input capture above takes priority over clearing a previous error pulse.
  when(io.demand.req) { bad(0) := io.demand.paddr(63,p.paddrBits).orR }
  when(io.prefetch.fire) { bad(1) := io.prefetch.bits(63,p.paddrBits).orR }
  when(io.coh.rspDown.fire) {
    assert(flight(io.coh.rspDown.bits.id), "unsolicited L1I response")
    assert(io.coh.rspDown.bits.op === RspDownOp.ReadData, "L1I requires ReadData")
    flight(io.coh.rspDown.bits.id) := false.B
  }
}

package flow.rvv

import chisel3._
import chisel3.util._
import flow.bus._
import flow.coherence._
import flow.config.BreezeMemGeometry

/** Single-hart write-through Home. It owns transactions and snoop completion,
  * stores no cache data or directory, and acknowledges writes after AXI B.
  * Invalidation admission waits for local scalar writers; this leaves the
  * Home request path open for the writer that an invalidation would wait on.
  */
class RvvDatalessHome(g: BreezeMemGeometry, memoryBits: Int) extends Module {
  require(g.nCores == 1)
  val p = CoherenceParams(g)
  val ap = Axi4Params(g.paddrBits, memoryBits, 1)
  val io = IO(new Bundle {
    val l1d = Flipped(new L1DCoherenceIO(p))
    val l1i = Flipped(new ReadClientIO(p))
    val dma = Flipped(new ReadClientIO(p))
    val invalidate = Flipped(Decoupled(UInt(64.W)))
    val scalarWritePending = Input(Bool())
    val scalarWritesVisible = Output(Bool())
    val mem = new Axi4MasterIO(ap)
  })
  val idle :: snoop :: snoopAck :: read :: readData :: write :: writeAck :: respond :: invDone :: Nil = Enum(9)
  val state = RegInit(idle)
  val q = Reg(new CoherenceReq(p))
  val source = Reg(UInt(2.W)) // scalar 0, instruction 1, SD 2, RVV invalidation 3
  val data = Reg(UInt(p.lineBits.W))
  val error = RegInit(false.B)
  val beat = RegInit(0.U(math.max(1, log2Ceil(p.lineBits / math.min(memoryBits, p.lineBits))).W))
  val awSent = RegInit(false.B); val wSent = RegInit(false.B)
  val memBytes = memoryBits / 8
  val beats = math.max(1, p.lineBits / memoryBits)
  val size = log2Ceil(math.min(memBytes, p.lineBytes))
  val address = q.addr ## 0.U(p.offBits.W)
  val laneShift = if (memBytes > p.lineBytes) address(log2Ceil(memBytes) - 1, p.offBits) ## 0.U(p.offBits.W) else 0.U(1.W)
  val lanes = if (memBytes > p.lineBytes) laneShift ## 0.U(3.W) else 0.U(1.W)
  val canInvalidate = !io.scalarWritePending
  // Registered ownership retains payload under all downstream backpressure.
  io.l1d.req.ready := state === idle
  io.l1i.req.ready := state === idle && !io.l1d.req.valid
  io.dma.req.ready := state === idle && !io.l1d.req.valid && !io.l1i.req.valid &&
    (io.dma.req.bits.op =/= ReqOp.MaskWrite || canInvalidate)
  io.invalidate.ready := state === invDone
  when(state === idle) {
    awSent := false.B; wSent := false.B; beat := 0.U; error := false.B; data := 0.U
    when(io.l1d.req.fire || io.l1i.req.fire || io.dma.req.fire) {
      val fromScalar = io.l1d.req.fire
      val fromInstruction = io.l1i.req.fire
      val selected = Mux(fromScalar, io.l1d.req.bits, Mux(fromInstruction, io.l1i.req.bits, io.dma.req.bits))
      q := selected
      source := Mux(fromScalar, 0.U, Mux(fromInstruction, 1.U, 2.U))
      state := Mux(selected.op === ReqOp.MaskWrite,
        Mux(fromScalar, write, snoop), read)
    }.elsewhen(io.invalidate.valid && canInvalidate) {
      q := 0.U.asTypeOf(q); q.addr := io.invalidate.bits(g.paddrBits - 1, p.offBits)
      source := 3.U; state := snoop
    }
  }
  io.l1d.snp.valid := state === snoop
  io.l1d.snp.bits := 0.U.asTypeOf(io.l1d.snp.bits)
  io.l1d.snp.bits.op := SnpOp.Inv
  io.l1d.snp.bits.addr := q.addr
  when(io.l1d.snp.fire) { state := snoopAck }
  val responses = Module(new Queue(new CoherenceRspDown(p), 4))
  io.l1d.rspDown <> responses.io.deq
  val scalarResponse = state === respond && source === 0.U
  val put = io.l1d.rspUp.bits.op === RspUpOp.Put
  io.l1d.rspUp.ready := Mux(put, responses.io.enq.ready && !scalarResponse, state === snoopAck)
  responses.io.enq.valid := scalarResponse || (io.l1d.rspUp.valid && put)
  responses.io.enq.bits := 0.U.asTypeOf(responses.io.enq.bits)
  responses.io.enq.bits.op := Mux(scalarResponse,
    Mux(q.op === ReqOp.MaskWrite, RspDownOp.WriteAck, RspDownOp.DataE), RspDownOp.PutAck)
  responses.io.enq.bits.id := Mux(scalarResponse, q.id, 0.U)
  responses.io.enq.bits.data := data
  responses.io.enq.bits.error := scalarResponse && error
  when(io.l1d.rspUp.fire) {
    assert(!io.l1d.rspUp.bits.hasData, "write-through Home received dirty data")
    when(!put) {
      assert(io.l1d.rspUp.bits.op === RspUpOp.InvAck && io.l1d.rspUp.bits.addr === q.addr,
        "invalidation acknowledgement lost its owner")
      state := Mux(source === 3.U, invDone, write)
    }
  }
  when(io.invalidate.fire) { state := idle }
  io.mem.ar.valid := state === read
  io.mem.ar.bits := 0.U.asTypeOf(io.mem.ar.bits)
  io.mem.ar.bits.addr := address; io.mem.ar.bits.len := (beats - 1).U
  io.mem.ar.bits.size := size.U; io.mem.ar.bits.burst := Axi4.BurstIncr
  when(io.mem.ar.fire) { state := readData }
  io.mem.r.ready := state === readData
  when(io.mem.r.fire) {
    assert(io.mem.r.bits.id === 0.U && io.mem.r.bits.last === (beat === (beats - 1).U), "Home read framing error")
    error := error || io.mem.r.bits.resp =/= 0.U
    if (memoryBits >= p.lineBits) { data := (io.mem.r.bits.data >> lanes)(p.lineBits - 1, 0) }
    else { data := data | (io.mem.r.bits.data.pad(p.lineBits) << (beat * memoryBits.U))(p.lineBits - 1, 0) }
    beat := beat + 1.U
    when(io.mem.r.bits.last) { state := respond }
  }
  io.mem.aw.valid := state === write && !awSent
  io.mem.aw.bits := 0.U.asTypeOf(io.mem.aw.bits)
  io.mem.aw.bits.addr := address; io.mem.aw.bits.len := (beats - 1).U
  io.mem.aw.bits.size := size.U; io.mem.aw.bits.burst := Axi4.BurstIncr
  io.mem.w.valid := state === write && !wSent
  io.mem.w.bits := 0.U.asTypeOf(io.mem.w.bits)
  if (memoryBits >= p.lineBits) {
    io.mem.w.bits.data := (q.data.pad(memoryBits) << lanes)(memoryBits - 1, 0)
    io.mem.w.bits.strb := (q.mask.pad(memBytes) << laneShift)(memBytes - 1, 0)
  } else {
    io.mem.w.bits.data := (q.data >> (beat * memoryBits.U))(memoryBits - 1, 0)
    io.mem.w.bits.strb := (q.mask >> (beat * memBytes.U))(memBytes - 1, 0)
  }
  io.mem.w.bits.last := beat === (beats - 1).U
  when(io.mem.aw.fire) { awSent := true.B }
  when(io.mem.w.fire) { beat := beat + 1.U; when(io.mem.w.bits.last) { wSent := true.B } }
  when(state === write && (awSent || io.mem.aw.fire) && (wSent || (io.mem.w.fire && io.mem.w.bits.last))) {
    state := writeAck
  }
  io.mem.b.ready := state === writeAck
  when(io.mem.b.fire) {
    assert(io.mem.b.bits.id === 0.U, "Home write response lost owner")
    error := io.mem.b.bits.resp =/= 0.U; state := respond
  }
  for ((port, n) <- Seq(io.l1i -> 1, io.dma -> 2)) {
    port.rspDown.valid := state === respond && source === n.U
    port.rspDown.bits := 0.U.asTypeOf(port.rspDown.bits)
    port.rspDown.bits.op := Mux(q.op === ReqOp.MaskWrite, RspDownOp.WriteAck, RspDownOp.ReadData)
    port.rspDown.bits.id := q.id; port.rspDown.bits.data := data; port.rspDown.bits.error := error
    when(port.rspDown.fire) { state := idle }
  }
  when(scalarResponse && responses.io.enq.fire) { state := idle }
  io.scalarWritesVisible := !(source === 0.U && state =/= idle && q.op === ReqOp.MaskWrite)
}

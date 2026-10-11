package flow.l1d

import chisel3._
import chisel3.util._
import flow.coherence._

/** One solo store owns its Home write through B/WriteAck. Local SRAM writes
  * are authorized only by a successful response, so a failed write cannot
  * leave a modified cache line behind. Payload never follows WB controls.
  */
class L1DWriteThrough(p: L1DParams) extends Module {
  val io = IO(new Bundle {
    val start = Flipped(Valid(new Bundle {
      val store = new L1PendingStore(p)
      val hit = Bool()
      val old = UInt(64.W)
    }))
    val req = Decoupled(new CoherenceReq(p.coh))
    val rsp = Flipped(Valid(new CoherenceRspDown(p.coh)))
    val consume = Input(Bool())
    val active = Output(Bool())
    val done = Output(Bool())
    val error = Output(Bool())
    val localWrite = Output(new L1PendingStore(p))
    val old = Output(UInt(64.W))
  })
  val idle :: send :: waitAck :: done :: Nil = Enum(4)
  val state = RegInit(idle)
  val saved = Reg(chiselTypeOf(io.start.bits))
  val failed = RegInit(false.B)
  io.active := state =/= idle
  io.done := state === done
  io.error := failed
  io.localWrite := saved.store
  io.localWrite.valid := state === done && !failed && saved.hit
  io.old := saved.old
  io.req.valid := state === send
  io.req.bits := 0.U.asTypeOf(io.req.bits)
  io.req.bits.op := ReqOp.MaskWrite
  io.req.bits.addr := saved.store.paddr(p.paddrBits - 1, p.offBits)
  val byteShift = saved.store.word ## 0.U(3.W)
  io.req.bits.mask := (saved.store.mask.pad(p.coh.lineBytes) << byteShift)(p.coh.lineBytes - 1, 0)
  io.req.bits.data := (saved.store.data.pad(p.coh.lineBytes * 8) << (byteShift ## 0.U(3.W)))(p.coh.lineBytes * 8 - 1, 0)
  when(io.start.valid) {
    assert(state === idle, "write-through owner overwritten")
    saved := io.start.bits
    failed := false.B
    state := send
  }
  when(io.req.fire) { state := waitAck }
  when(io.rsp.valid) {
    assert(state === waitAck && io.rsp.bits.op === RspDownOp.WriteAck, "write-through response lost owner")
    failed := io.rsp.bits.error
    state := done
  }
  when(io.consume) { assert(state === done); state := idle }
}

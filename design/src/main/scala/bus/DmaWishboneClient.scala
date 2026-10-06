package flow.bus

import chisel3._
import chisel3.util._
import flow.coherence._
import flow.config.BreezeMemGeometry

/** One Wishbone beat maps to one DMA Read or byte-masked line MaskWrite.
  * No cache/directory sharer identity is attached to a DMA transaction.
  */
class DmaWishboneClient(g: BreezeMemGeometry) extends Module {
  val p=CoherenceParams(g)
  val wb=LiteXWishboneParameters(p.paddrBits,64)
  val io=IO(new Bundle {
    val wishbone=Flipped(new LiteXWishboneMasterIO(wb))
    val coh=new ReadClientIO(p)
  })
  object St extends ChiselEnum { val Idle, Send, Wait, Respond = Value }
  val state=RegInit(St.Idle)
  val request=Reg(new CoherenceReq(p))
  val word=Reg(UInt(log2Ceil(p.lineBytes/8).W))
  val data=Reg(UInt(64.W)); val error=Reg(Bool())
  when(state===St.Idle && io.wishbone.cyc && io.wishbone.stb) {
    val byteAddress=io.wishbone.adr ## 0.U(3.W)
    val byteOffset=byteAddress(p.offBits-1,0)
    word:=byteAddress(p.offBits-1,3)
    request.op:=Mux(io.wishbone.we,ReqOp.MaskWrite,ReqOp.Read)
    request.addr:=byteAddress(p.paddrBits-1,p.offBits); request.id:=0.U
    request.mask:=io.wishbone.sel << byteOffset
    request.data:=io.wishbone.dat_w << (byteOffset ## 0.U(3.W))
    state:=St.Send
  }
  io.coh.req.valid:=state===St.Send; io.coh.req.bits:=request
  when(io.coh.req.fire) { state:=St.Wait }
  io.coh.rspDown.ready:=state===St.Wait
  when(io.coh.rspDown.fire) {
    assert(io.coh.rspDown.bits.id===0.U)
    assert(io.coh.rspDown.bits.op===Mux(request.op===ReqOp.Read,RspDownOp.ReadData,RspDownOp.WriteAck))
    data:=(io.coh.rspDown.bits.data >> (word ## 0.U(6.W)))(63,0)
    error:=io.coh.rspDown.bits.error; state:=St.Respond
  }
  io.wishbone.ack:=state===St.Respond && io.wishbone.cyc && io.wishbone.stb && !error
  io.wishbone.err:=state===St.Respond && io.wishbone.cyc && io.wishbone.stb && error
  io.wishbone.dat_r:=data
  when(state===St.Respond) { state:=St.Idle }
}

package flow.bus

import chisel3._
import chisel3.util._

/** Serialized 64-bit AXI INCR bursts to classic LiteX Wishbone beats.
  * Independent AW/AR/W queues avoid assuming an AW/W arrival order. R/B
  * responses are held under backpressure; IDs and LAST come from accepted
  * addresses. The implemented memory engine uses SIZE=3, LEN=3.
  */
class Axi4WishboneBridge(p: Axi4Params) extends Module {
  require(p.dataBits==64)
  val io=IO(new Bundle {
    val axi=Flipped(new Axi4MasterIO(p))
    val wishbone=new LiteXWishboneMasterIO(LiteXWishboneParameters(p.addrBits,p.dataBits))
  })
  val ar=Module(new Queue(new Axi4Ar(p),1)); ar.io.enq<>io.axi.ar
  val aw=Module(new Queue(new Axi4Aw(p),1)); aw.io.enq<>io.axi.aw
  val w=Module(new Queue(new Axi4W(p),1)); w.io.enq<>io.axi.w
  object St extends ChiselEnum { val Idle, ReadBeat, ReadResponse, WriteBeat, WriteResponse = Value }
  val state=RegInit(St.Idle)
  val address=Reg(UInt(p.addrBits.W)); val id=Reg(UInt(p.idBits.W)); val len=Reg(UInt(8.W))
  val beat=Reg(UInt(8.W)); val data=Reg(UInt(64.W)); val error=RegInit(false.B)
  val lastWrite=RegInit(false.B)
  val chooseWrite=aw.io.deq.valid && (!ar.io.deq.valid || !lastWrite)
  aw.io.deq.ready:=state===St.Idle && chooseWrite
  ar.io.deq.ready:=state===St.Idle && !chooseWrite
  when(aw.io.deq.fire || ar.io.deq.fire) {
    val req=Wire(new Axi4Ar(p))
    req:=ar.io.deq.bits
    when(chooseWrite) { req:=aw.io.deq.bits.asTypeOf(new Axi4Ar(p)) }
    assert(req.size===3.U && req.burst===Axi4.BurstIncr,"bridge supports 64-bit INCR transfers")
    address:=req.addr; id:=req.id; len:=req.len; beat:=0.U; error:=false.B
    state:=Mux(chooseWrite,St.WriteBeat,St.ReadBeat); lastWrite:=chooseWrite
  }
  val readBeat=state===St.ReadBeat
  val writeBeat=state===St.WriteBeat && w.io.deq.valid
  io.wishbone.cyc:=readBeat || writeBeat; io.wishbone.stb:=io.wishbone.cyc
  io.wishbone.we:=writeBeat; io.wishbone.adr:=address(p.addrBits-1,3)
  io.wishbone.dat_w:=w.io.deq.bits.data; io.wishbone.sel:=Mux(writeBeat,w.io.deq.bits.strb,"hff".U)
  io.wishbone.cti:=WishboneCycleType.Classic; io.wishbone.bte:=WishboneBurstType.Linear
  val complete=io.wishbone.cyc && (io.wishbone.ack || io.wishbone.err)
  w.io.deq.ready:=writeBeat && complete
  when(readBeat && complete) {
    data:=io.wishbone.dat_r; error:=io.wishbone.err; state:=St.ReadResponse
  }
  when(writeBeat && complete) {
    error:=error || io.wishbone.err
    assert(w.io.deq.bits.last===(beat===len),"AXI WLAST does not match AWLEN")
    when(beat===len) { state:=St.WriteResponse }
      .otherwise { beat:=beat+1.U; address:=address+8.U }
  }
  io.axi.r.valid:=state===St.ReadResponse
  io.axi.r.bits.id:=id; io.axi.r.bits.data:=data
  io.axi.r.bits.resp:=Mux(error,2.U,Axi4.RespOkay); io.axi.r.bits.last:=beat===len
  when(io.axi.r.fire) {
    when(beat===len) { state:=St.Idle }
      .otherwise { beat:=beat+1.U; address:=address+8.U; state:=St.ReadBeat }
  }
  io.axi.b.valid:=state===St.WriteResponse; io.axi.b.bits.id:=id
  io.axi.b.bits.resp:=Mux(error,2.U,Axi4.RespOkay)
  when(io.axi.b.fire) { state:=St.Idle }
}

class Axi4LiteWishboneBridge(addrBits: Int=32) extends Module {
  val io=IO(new Bundle {
    val axi=Flipped(new Axi4LiteMasterIO(addrBits,64))
    val wishbone=new LiteXWishboneMasterIO(LiteXWishboneParameters(addrBits,64))
  })
  val bridge=Module(new Axi4WishboneBridge(Axi4Params(addrBits,64,1)))
  for ((in,out)<-Seq(io.axi.ar->bridge.io.axi.ar,io.axi.aw->bridge.io.axi.aw)) {
    out.valid:=in.valid; in.ready:=out.ready
    out.bits:=0.U.asTypeOf(out.bits); out.bits.addr:=in.bits.addr; out.bits.prot:=in.bits.prot
    out.bits.size:=3.U; out.bits.burst:=Axi4.BurstIncr
  }
  bridge.io.axi.w.valid:=io.axi.w.valid; io.axi.w.ready:=bridge.io.axi.w.ready
  bridge.io.axi.w.bits.data:=io.axi.w.bits.data; bridge.io.axi.w.bits.strb:=io.axi.w.bits.strb
  bridge.io.axi.w.bits.last:=true.B
  io.axi.r.valid:=bridge.io.axi.r.valid; bridge.io.axi.r.ready:=io.axi.r.ready
  io.axi.r.bits.data:=bridge.io.axi.r.bits.data; io.axi.r.bits.resp:=bridge.io.axi.r.bits.resp
  io.axi.b.valid:=bridge.io.axi.b.valid; bridge.io.axi.b.ready:=io.axi.b.ready
  io.axi.b.bits:=bridge.io.axi.b.bits.resp
  io.wishbone<>bridge.io.wishbone
}

package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.rvv.RvvAxi

/** Precise element/field engine behind a SoC-owned translation and PA lease
  * service. Aligned elements use narrow AXI transfers; unaligned elements use
  * bytes so page/PMP boundaries are checked without over-reading. A load field
  * becomes visible only after all of its chunks complete successfully. */
class ZveMemoryEngine(p: ZveParams) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ZveCommand(p)))
    val vrf=new ZveVrfPort(p); val mask=Input(UInt(p.vrf.vlen.W))
    val translation=Decoupled(new ZveTranslationRequest(p))
    val translated=Flipped(Decoupled(new ZveTranslationResponse(p)))
    val order=Decoupled(new ZveMemoryOrder); val release=Decoupled(UInt(16.W))
    val invalidate=Decoupled(UInt(64.W)); val scalarWritesVisible=Input(Bool())
    val axi=new RvvAxi(p.vrf)
    val complete=Decoupled(new ZveCompletion(p)); val drained=Output(Bool())
    val scalarMemoryBlocked=Output(Bool())
  })
  val idle::elementCheck::source::sourceWait::address::translate::translationWait::order::invalidate::loadRequest::loadWait::storeRequest::storeWait::publish::publishWait::release::advance::finish::Nil=Enum(18)
  val state=RegInit(idle); val d=Reg(new ZveCommand(p)); val mask=Reg(UInt(p.vrf.vlen.W))
  val element=Reg(UInt((p.vrf.lengthBits+1).W)); val field=Reg(UInt(3.W)); val chunk=Reg(UInt(3.W))
  val tag=Reg(UInt(16.W)); val nextTag=RegInit(0.U(16.W))
  val index=Reg(UInt(64.W)); val storeData=Reg(UInt(32.W)); val assembled=Reg(UInt(32.W))
  val va=Reg(UInt(64.W)); val pa=Reg(UInt(64.W)); val chunkLog=Reg(UInt(2.W))
  val awSent=RegInit(false.B); val wSent=RegInit(false.B)
  val stop=RegInit(false.B); val exception=RegInit(false.B); val cause=Reg(UInt(64.W)); val tval=Reg(UInt(64.W))
  val shorten=RegInit(false.B); val shortenedVl=Reg(UInt(p.vrf.lengthBits.W))
  val x=d.issue.scalar.instruction; val store=x(6,0)===0x27.U
  val mop=x(27,26); val indexed=mop(0); val strided=mop===2.U
  val whole=d.whole; val maskMemory=mop===0.U && x(24,20)===11.U
  val faultOnlyFirst = !store && mop===0.U && x(24,20)===16.U
  val width=Mux(maskMemory,0.U,d.destinationWidth)
  val dataBytes=1.U(3.W)<<width
  val fields=Mux(whole || maskMemory,1.U,x(31,29)+&1.U)
  val limit=Mux(whole,((x(31,29)+&1.U)*p.vrf.regBytes.U)>>width,
    Mux(maskMemory,(d.issue.scalar.vl+&7.U)>>3,d.issue.scalar.vl))
  val destinationByte=Mux(whole || maskMemory,element<<width,
    field*d.destinationRegisters*p.vrf.regBytes.U+(element<<width))
  val destinationRow=(x(11,7)*p.vrf.rowsPerReg.U+(destinationByte>>log2Ceil(p.vrf.rowBytes)))(p.vrf.rowBits-1,0)
  val destinationOffset=destinationByte(log2Ceil(p.vrf.rowBytes)-1,0)
  val indexByte=element<<d.source2Width
  val indexRow=(x(24,20)*p.vrf.rowsPerReg.U+(indexByte>>log2Ceil(p.vrf.rowBytes)))(p.vrf.rowBits-1,0)
  io.in.ready := state===idle
  when(io.in.fire) {
    d := io.in.bits; mask := io.mask; element := io.in.bits.issue.scalar.vstart; field := 0.U; chunk := 0.U
    stop := false.B; exception := false.B; shorten := false.B; assembled := 0.U
    cause := 0.U; tval := 0.U; shortenedVl := io.in.bits.issue.scalar.vl; state := elementCheck
  }
  when(state===elementCheck) {
    when(element>=limit) { state := finish }
      .elsewhen(!whole && !maskMemory && !x(25) && !(mask>>element)(0)) { element := element+1.U }
      .otherwise { tag := nextTag; nextTag := nextTag+1.U; chunk := 0.U; assembled := 0.U
        state := Mux(indexed || store,source,address); index := 0.U; storeData := 0.U }
  }
  io.vrf.read.valid := state===source
  io.vrf.read.bits.tag := tag; io.vrf.read.bits.age := d.age
  io.vrf.read.bits.rows(0) := indexRow; io.vrf.read.bits.rows(1) := destinationRow; io.vrf.read.bits.rows(2) := 0.U
  io.vrf.read.bits.enables := Cat(false.B,store,indexed)
  when(io.vrf.read.fire) { state := sourceWait }
  io.vrf.returned.ready := state===sourceWait
  when(io.vrf.returned.fire) {
    assert(io.vrf.returned.bits.tag===tag,"memory VRF return has the wrong owner")
    val raw=(io.vrf.returned.bits.data(0)>>(indexByte(log2Ceil(p.vrf.rowBytes)-1,0)<<3))(31,0)
    index := MuxLookup(d.source2Width,raw)(Seq(0.U->raw(7,0).pad(32),1.U->raw(15,0).pad(32)))
    storeData := (io.vrf.returned.bits.data(1)>>(destinationOffset<<3))(31,0)
    state := address
  }
  when(state===address) {
    val position=Mux(whole || maskMemory,element*dataBytes,
      Mux(indexed,index,Mux(strided,element*d.issue.scalar.rs2,element*fields*dataBytes)))+field*dataBytes
    val base=d.issue.scalar.rs1+position
    va := base; chunkLog := Mux((base & (dataBytes-1.U))===0.U,width,0.U)
    state := translate
  }
  io.translation.valid := state===translate
  io.translation.bits.tag := tag; io.translation.bits.va := va+chunk
  io.translation.bits.bytes := 1.U<<chunkLog; io.translation.bits.store := store
  io.translation.bits.epoch := d.issue.epoch
  when(io.translation.fire) { state := translationWait }
  io.translated.ready := state===translationWait
  def fault(code: UInt,badAddress: UInt,hasLease: Boolean): Unit = {
    stop := true.B; cause := code; tval := badAddress
    when(faultOnlyFirst && element=/=0.U) { shorten := true.B; shortenedVl := element; exception := false.B }
      .otherwise { exception := true.B }
    state := (if(hasLease) release else finish)
  }
  when(io.translated.fire) {
    assert(io.translated.bits.tag===tag && io.translated.bits.epoch===d.issue.epoch,"translation lost tag/epoch")
    when(io.translated.bits.exception || io.translated.bits.device) {
      fault(Mux(io.translated.bits.exception,io.translated.bits.cause,Mux(store,7.U,5.U)),
        Mux(io.translated.bits.exception,io.translated.bits.tval,va+chunk),false)
    }.otherwise {
      assert(io.translated.bits.bytes >= (1.U<<chunkLog),"translation did not authorize the entire chunk")
      assert((io.translated.bits.pa>>p.vrf.paBits)===0.U,"translated PA exceeds platform width")
      pa := io.translated.bits.pa; state := order
    }
  }
  io.order.valid := state===order && io.scalarWritesVisible
  io.order.bits.tag := tag; io.order.bits.age := d.issue.memoryAge
  io.order.bits.pa := pa; io.order.bits.bytes := 1.U<<chunkLog; io.order.bits.store := store
  when(io.order.fire) { state := Mux(store,invalidate,loadRequest); awSent := false.B; wSent := false.B }
  io.invalidate.valid := state===invalidate
  io.invalidate.bits := pa & (~(p.vrf.cacheLineBytes-1).U(64.W))
  when(io.invalidate.fire) { state := storeRequest }
  io.axi.ar.valid := state===loadRequest
  io.axi.ar.bits := 0.U.asTypeOf(io.axi.ar.bits)
  io.axi.ar.bits.addr := pa; io.axi.ar.bits.size := chunkLog; io.axi.ar.bits.burst := 1.U
  when(io.axi.ar.fire) { state := loadWait }
  io.axi.r.ready := state===loadWait
  val chunkMask=MuxLookup(chunkLog,"hffffffff".U(32.W))(Seq(0.U->"hff".U(32.W),1.U->"hffff".U(32.W)))
  val busOffset=pa(log2Ceil(p.vrf.memBytes)-1,0)
  when(io.axi.r.fire) {
    assert(io.axi.r.bits.id===0.U && io.axi.r.bits.last,"memory read framing error")
    when(io.axi.r.bits.resp=/=0.U) { fault(5.U,va+chunk,true) }
      .otherwise {
        val data=(io.axi.r.bits.data>>(busOffset<<3))(31,0) & chunkMask
        val shiftedMask=(chunkMask<<(chunk<<3))(31,0)
        assembled := (assembled & ~shiftedMask) | ((data<<(chunk<<3))(31,0))
        state := Mux(chunk+(1.U<<chunkLog)>=dataBytes,publish,release)
      }
  }
  io.axi.aw.valid := state===storeRequest && !awSent
  io.axi.aw.bits := io.axi.ar.bits
  io.axi.w.valid := state===storeRequest && !wSent
  io.axi.w.bits := 0.U.asTypeOf(io.axi.w.bits)
  io.axi.w.bits.data := (((storeData>>(chunk<<3)) & chunkMask).pad(p.vrf.memoryBits)<<(busOffset<<3))(p.vrf.memoryBits-1,0)
  io.axi.w.bits.strb := ((((1.U((p.vrf.memBytes+1).W)<<(1.U<<chunkLog))-1.U)<<busOffset))(p.vrf.memBytes-1,0)
  io.axi.w.bits.last := true.B
  when(io.axi.aw.fire) { awSent := true.B }
  when(io.axi.w.fire) { wSent := true.B }
  when(state===storeRequest && (awSent || io.axi.aw.fire) && (wSent || io.axi.w.fire)) { state := storeWait }
  io.axi.b.ready := state===storeWait
  when(io.axi.b.fire) {
    assert(io.axi.b.bits.id===0.U,"memory write response has the wrong AXI ID")
    when(io.axi.b.bits.resp=/=0.U) { fault(7.U,va+chunk,true) }.otherwise { state := release }
  }
  io.vrf.write.valid := state===publish
  io.vrf.write.bits.tag := tag; io.vrf.write.bits.age := d.age; io.vrf.write.bits.row := destinationRow
  io.vrf.write.bits.data := (assembled.pad(p.vrf.dlen)<<(destinationOffset<<3))(p.vrf.dlen-1,0)
  io.vrf.write.bits.enables := ((((1.U((p.vrf.rowBytes+1).W)<<dataBytes)-1.U)<<destinationOffset))(p.vrf.rowBytes-1,0)
  when(io.vrf.write.fire) { state := publishWait }
  io.vrf.written.ready := state===publishWait
  when(io.vrf.written.fire) { assert(io.vrf.written.bits.tag===tag,"memory publish ack has the wrong owner"); state := release }
  io.release.valid := state===release; io.release.bits := tag
  when(io.release.fire) {
    when(stop) { state := finish }
      .elsewhen(chunk+(1.U<<chunkLog)<dataBytes) {
        chunk := chunk+(1.U<<chunkLog); tag := nextTag; nextTag := nextTag+1.U; state := translate
      }.otherwise { state := advance }
  }
  when(state===advance) {
    when(field+&1.U<fields) { field := field+1.U }
      .otherwise { field := 0.U; element := element+1.U }
    state := elementCheck
  }
  io.complete.valid := state===finish
  io.complete.bits := 0.U.asTypeOf(new ZveCompletion(p))
  io.complete.bits.slot := d.slot; io.complete.bits.age := d.age
  io.complete.bits.exception := exception; io.complete.bits.cause := cause; io.complete.bits.tval := tval
  io.complete.bits.vstart := Mux(exception,element,0.U)
  io.complete.bits.changeVl := shorten; io.complete.bits.newVl := shortenedVl
  when(io.complete.fire) { state := idle }
  io.drained := state===idle
  io.scalarMemoryBlocked := state=/=idle
}

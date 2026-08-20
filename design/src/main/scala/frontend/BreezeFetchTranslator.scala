package flow.frontend

import chisel3._
import chisel3.util._
import flow.interface._

/** Blocking instruction-side address-translation adapter. */
class BreezeFetchTranslator(val xlen: Int = 64) extends Module {
  val io = IO(new Bundle {
    val inReq = Flipped(Decoupled(new BreezeCacheReqIO(xlen)))
    val inRsp = Decoupled(new BreezeCacheRespIO(xlen, 32))
    val cacheReq = Decoupled(new BreezeCacheReqIO(xlen))
    val cacheRsp = Flipped(Decoupled(new BreezeCacheRespIO(xlen, 32)))
    val translateReq = Decoupled(new BreezeTranslationReq(xlen))
    val translateRsp = Flipped(Decoupled(new BreezeTranslationResp(xlen)))
    val kill = Input(Bool())
  })

  object State extends ChiselEnum { val Idle, Translate, CacheReq, CacheWait, Fault = Value }
  import State._
  val state = RegInit(Idle)
  val vaddr = Reg(UInt(xlen.W))
  val paddr = Reg(UInt(xlen.W))
  val pageFault = RegInit(false.B)
  val accessFault = RegInit(false.B)

  io.inReq.ready := state === Idle && !io.kill
  io.translateReq.valid := state === Translate && !io.kill
  io.translateReq.bits.vaddr := vaddr
  io.translateReq.bits.access := BreezeMmuAccess.Fetch
  io.translateReq.bits.sizeLog2 := 2.U
  io.translateRsp.ready := state === Translate
  io.cacheReq.valid := state === CacheReq && !io.kill
  io.cacheReq.bits.vaddr := vaddr
  io.cacheReq.bits.paddr := paddr
  io.cacheRsp.ready := state === CacheWait
  io.inRsp.valid := (state === Fault || (state === CacheWait && io.cacheRsp.valid)) && !io.kill
  io.inRsp.bits.vaddr := vaddr
  io.inRsp.bits.data := Mux(state === CacheWait, io.cacheRsp.bits.data, 0.U)
  io.inRsp.bits.accessFault := Mux(state === CacheWait, io.cacheRsp.bits.accessFault, accessFault)
  io.inRsp.bits.pageFault := Mux(state === CacheWait, io.cacheRsp.bits.pageFault, pageFault)

  when(io.kill) {
    state := Idle
  }.otherwise {
    switch(state) {
      is(Idle) { when(io.inReq.fire) { vaddr := io.inReq.bits.vaddr; state := Translate } }
      is(Translate) {
        when(io.translateRsp.fire) {
          paddr := io.translateRsp.bits.paddr
          pageFault := io.translateRsp.bits.pageFault
          accessFault := io.translateRsp.bits.accessFault
          state := Mux(io.translateRsp.bits.pageFault || io.translateRsp.bits.accessFault,
            Fault, CacheReq)
        }
      }
      is(CacheReq) { when(io.cacheReq.fire) { state := CacheWait } }
      is(CacheWait) { when(io.inRsp.fire) { state := Idle } }
      is(Fault) { when(io.inRsp.fire) { state := Idle } }
    }
  }
}

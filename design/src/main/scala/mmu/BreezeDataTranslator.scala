package flow.mmu

import chisel3._
import chisel3.util._
import flow.interface._

/** Blocking D-side translation adapter.  It preserves the backend's pulse
  * request protocol and converts translation faults into precise responses.
  */
class BreezeDataTranslator(val xlen: Int = 64, val parallelLookup: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val cpu = Flipped(new BackendMemIO(xlen))
    val translateReq = Decoupled(new BreezeTranslationReq(xlen))
    val translateRsp = Flipped(Decoupled(new BreezeTranslationResp(xlen)))
    val memReq = Decoupled(new BackendMemReq(xlen))
    val memRsp = Flipped(Decoupled(new BackendMemResp))
    val arrayReq = if (parallelLookup) Some(Decoupled(UInt(xlen.W))) else None
  })

  object State extends ChiselEnum { val Idle, Translate, MemReq, MemWait, Fault, Respond = Value }
  import State._
  val state = RegInit(Idle)
  val req = Reg(new BackendMemReq(xlen))
  val paddr = Reg(UInt(xlen.W))
  val rsp = Reg(new BackendMemResp)
  if (parallelLookup) {
    val sent = RegInit(false.B)
    when(state === Idle) { sent := false.B }
    io.arrayReq.get.valid := state === Translate && !sent
    io.arrayReq.get.bits := req.addr
    when(io.arrayReq.get.fire) { sent := true.B }
  }

  io.translateReq.valid := state === Translate
  io.translateReq.bits.vaddr := req.addr
  io.translateReq.bits.access := Mux(req.isWrite || req.memOp === BreezeMemOp.Sc ||
    req.memOp === BreezeMemOp.Amo, BreezeMmuAccess.Store, BreezeMmuAccess.Load)
  io.translateReq.bits.sizeLog2 := req.sizeLog2
  io.translateRsp.ready := state === Translate
  io.memReq.valid := state === MemReq
  io.memReq.bits := req
  io.memReq.bits.valid := true.B
  io.memReq.bits.addr := paddr
  io.memRsp.ready := state === MemWait
  io.cpu.rsp := rsp
  io.cpu.rsp.valid := state === Respond

  when(state === Idle && io.cpu.req.valid) {
    req := io.cpu.req
    state := Translate
  }
  switch(state) {
    is(Translate) {
      when(io.translateRsp.fire) {
        paddr := io.translateRsp.bits.paddr
        when(io.translateRsp.bits.pageFault || io.translateRsp.bits.accessFault) {
          rsp.data := 0.U
          rsp.isWriteAck := false.B
          rsp.error := true.B
          rsp.pageFault := io.translateRsp.bits.pageFault
          rsp.faultAddr := req.addr
          state := Respond
        }.otherwise { state := MemReq }
      }
    }
    is(MemReq) { when(io.memReq.fire) { state := MemWait } }
    is(MemWait) {
      when(io.memRsp.fire) { rsp := io.memRsp.bits; state := Respond }
    }
    is(Respond) { state := Idle }
  }

  assert(!(state =/= Idle && io.cpu.req.valid),
    "[BreezeDataTranslator] backend issued a second request while translation was busy")
}

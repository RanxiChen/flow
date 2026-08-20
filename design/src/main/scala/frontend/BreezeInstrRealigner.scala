package flow.frontend

import chisel3._
import chisel3.util._
import flow.interface._

class BreezeInstrRealignerReq(val vlen: Int) extends Bundle {
  val pc = UInt(vlen.W)
}

class BreezeInstrRealignerResp(val vlen: Int) extends Bundle {
  val pc = UInt(vlen.W)
  val rawInst = UInt(32.W)
  val instLen = UInt(3.W)
  val isCompressed = Bool()
  val accessFault = Bool()
  val pageFault = Bool()
  val faultVaddr = UInt(vlen.W)
}

/** Turns a stream of aligned 32-bit ICache words into one 16/32-bit RISC-V
  * instruction.  It is intentionally blocking: the core is single issue and
  * an unaligned 32-bit instruction may require a second cache access.
  */
class BreezeInstrRealigner(val vlen: Int = 64) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new BreezeInstrRealignerReq(vlen)))
    val resp = Decoupled(new BreezeInstrRealignerResp(vlen))
    val wordReq = Decoupled(new BreezeCacheReqIO(vlen))
    val wordRsp = Flipped(Decoupled(new BreezeCacheRespIO(vlen, 32)))
    val redirect = Input(Bool())
  })

  object State extends ChiselEnum {
    val Idle, RequestFirst, WaitFirst, InspectFirst,
        RequestSecond, WaitSecond, Emit = Value
  }
  import State._
  val state = RegInit(Idle)
  val pcReg = Reg(UInt(vlen.W))
  val firstWordReg = Reg(UInt(32.W))
  val firstFaultReg = RegInit(false.B)
  val firstPageFaultReg = RegInit(false.B)
  val rawInstReg = Reg(UInt(32.W))
  val instLenReg = RegInit(4.U(3.W))
  val compressedReg = RegInit(false.B)
  val accessFaultReg = RegInit(false.B)
  val pageFaultReg = RegInit(false.B)
  val faultVaddrReg = Reg(UInt(vlen.W))

  val wordBufValid = RegInit(false.B)
  val wordBufAddr = Reg(UInt(vlen.W))
  val wordBufData = Reg(UInt(32.W))
  val wordBufFault = RegInit(false.B)
  val wordBufPageFault = RegInit(false.B)

  val firstAddr = pcReg & ~3.U(vlen.W)
  val secondAddr = firstAddr + 4.U

  io.req.ready := state === Idle && !io.redirect
  io.wordReq.valid := false.B
  io.wordReq.bits.vaddr := 0.U
  io.wordReq.bits.paddr := 0.U
  io.wordRsp.ready := true.B
  io.resp.valid := state === Emit && !io.redirect
  io.resp.bits.pc := pcReg
  io.resp.bits.rawInst := rawInstReg
  io.resp.bits.instLen := instLenReg
  io.resp.bits.isCompressed := compressedReg
  io.resp.bits.accessFault := accessFaultReg
  io.resp.bits.pageFault := pageFaultReg
  io.resp.bits.faultVaddr := faultVaddrReg

  when(io.redirect) {
    state := Idle
    wordBufValid := false.B
  }.otherwise {
    switch(state) {
      is(Idle) {
        when(io.req.fire) {
          pcReg := io.req.bits.pc
          state := RequestFirst
        }
      }
      is(RequestFirst) {
        when(wordBufValid && wordBufAddr === firstAddr) {
          firstWordReg := wordBufData
          firstFaultReg := wordBufFault
          firstPageFaultReg := wordBufPageFault
          state := InspectFirst
        }.otherwise {
          io.wordReq.valid := true.B
          io.wordReq.bits.vaddr := firstAddr
          when(io.wordReq.fire) { state := WaitFirst }
        }
      }
      is(WaitFirst) {
        when(io.wordRsp.fire && io.wordRsp.bits.vaddr === firstAddr) {
          firstWordReg := io.wordRsp.bits.data
          firstFaultReg := io.wordRsp.bits.accessFault
          firstPageFaultReg := io.wordRsp.bits.pageFault
          wordBufValid := true.B
          wordBufAddr := firstAddr
          wordBufData := io.wordRsp.bits.data
          wordBufFault := io.wordRsp.bits.accessFault
          wordBufPageFault := io.wordRsp.bits.pageFault
          state := InspectFirst
        }
      }
      is(InspectFirst) {
        val parcel = Mux(pcReg(1), firstWordReg(31, 16), firstWordReg(15, 0))
        val compressed = parcel(1, 0) =/= 3.U
        when(firstFaultReg || firstPageFaultReg) {
          rawInstReg := 0.U
          instLenReg := 2.U
          compressedReg := false.B
          accessFaultReg := firstFaultReg
          pageFaultReg := firstPageFaultReg
          faultVaddrReg := pcReg
          state := Emit
        }.elsewhen(compressed) {
          rawInstReg := Cat(0.U(16.W), parcel)
          instLenReg := 2.U
          compressedReg := true.B
          accessFaultReg := false.B
          pageFaultReg := false.B
          faultVaddrReg := 0.U
          state := Emit
        }.elsewhen(!pcReg(1)) {
          rawInstReg := firstWordReg
          instLenReg := 4.U
          compressedReg := false.B
          accessFaultReg := false.B
          pageFaultReg := false.B
          faultVaddrReg := 0.U
          state := Emit
        }.otherwise {
          state := RequestSecond
        }
      }
      is(RequestSecond) {
        when(wordBufValid && wordBufAddr === secondAddr) {
          rawInstReg := Cat(wordBufData(15, 0), firstWordReg(31, 16))
          instLenReg := 4.U
          compressedReg := false.B
          accessFaultReg := wordBufFault
          pageFaultReg := wordBufPageFault
          faultVaddrReg := Mux(wordBufFault, secondAddr, 0.U)
          state := Emit
        }.otherwise {
          io.wordReq.valid := true.B
          io.wordReq.bits.vaddr := secondAddr
          when(io.wordReq.fire) { state := WaitSecond }
        }
      }
      is(WaitSecond) {
        when(io.wordRsp.fire && io.wordRsp.bits.vaddr === secondAddr) {
          rawInstReg := Cat(io.wordRsp.bits.data(15, 0), firstWordReg(31, 16))
          instLenReg := 4.U
          compressedReg := false.B
          accessFaultReg := io.wordRsp.bits.accessFault
          pageFaultReg := io.wordRsp.bits.pageFault
          faultVaddrReg := Mux(io.wordRsp.bits.accessFault, secondAddr, 0.U)
          wordBufValid := true.B
          wordBufAddr := secondAddr
          wordBufData := io.wordRsp.bits.data
          wordBufFault := io.wordRsp.bits.accessFault
          wordBufPageFault := io.wordRsp.bits.pageFault
          state := Emit
        }
      }
      is(Emit) {
        when(io.resp.fire) { state := Idle }
      }
    }
  }
}

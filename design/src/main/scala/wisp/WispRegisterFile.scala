package flow.wisp

import chisel3._
import chisel3.util.Cat

/** 32 x 64 register file physically organized as 256 x 8 bits.
  * Two synchronous reads and one write share the same inferred RAM.  The core
  * never asks for a read and a write in the same cycle.
  */
class WispRegisterFile extends Module {
  val io = IO(new Bundle {
    val readEnable = Input(Bool())
    val readRegA = Input(UInt(5.W))
    val readRegB = Input(UInt(5.W))
    val readByte = Input(UInt(3.W))
    val readDataA = Output(UInt(8.W))
    val readDataB = Output(UInt(8.W))
    val writeEnable = Input(Bool())
    val writeReg = Input(UInt(5.W))
    val writeByte = Input(UInt(3.W))
    val writeData = Input(UInt(8.W))
  })

  val memory = SyncReadMem(256, UInt(8.W))
  val addrA = Cat(io.readRegA, io.readByte)
  val addrB = Cat(io.readRegB, io.readByte)
  // Port A is time-multiplexed between read and write.  Wisp never reads and
  // commits in the same cycle, so this describes a physical true-dual-port
  // RAM (A: read/write, B: read) instead of an uninferable three-port RAM.
  val portAWrite = io.writeEnable && io.writeReg =/= 0.U
  val portAAddr = Mux(portAWrite, Cat(io.writeReg, io.writeByte), addrA)
  val rawA = memory.readWrite(portAAddr, io.writeData,
    io.readEnable || portAWrite, portAWrite)
  val rawB = memory.read(addrB, io.readEnable)
  io.readDataA := Mux(RegNext(io.readEnable && io.readRegA =/= 0.U, false.B), rawA, 0.U)
  io.readDataB := Mux(RegNext(io.readEnable && io.readRegB =/= 0.U, false.B), rawB, 0.U)
}

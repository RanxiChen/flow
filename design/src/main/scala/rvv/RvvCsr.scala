package flow.rvv

import chisel3._
import chisel3.util._

class RvvCsrState(p: RvvParams) extends Bundle {
  val vl = UInt(p.lengthBits.W); val vtype = UInt(64.W)
  val vstart = UInt(p.lengthBits.W); val vxrm = UInt(2.W)
  val enabled = Bool()
}
class RvvCsrCommit(p: RvvParams) extends Bundle {
  val valid = Bool(); val set = Bool(); val vl = UInt(p.lengthBits.W); val vtype = UInt(64.W)
  val trap = Bool(); val vstart = UInt(p.lengthBits.W)
  val vxsat = Bool()
}
class RvvCsr(p: RvvParams) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(12.W)); val read = Output(UInt(64.W)); val exists = Output(Bool())
    val write = Input(Bool()); val writeAddress = Input(UInt(12.W)); val writeData = Input(UInt(64.W))
    val commit = Input(new RvvCsrCommit(p)); val state = Output(new RvvCsrState(p))
    val vs = Output(UInt(2.W))
  })
  val vs = RegInit(0.U(2.W)); val vl = RegInit(0.U(p.lengthBits.W))
  val vtype = RegInit((BigInt(1) << 63).U(64.W))
  val vstart = RegInit(0.U(p.lengthBits.W)); val vxrm = RegInit(0.U(2.W)); val vxsat = RegInit(false.B)
  def entry(address: Int, data: UInt): (Int, UInt) = (address, data.pad(64))
  val reads = Seq(entry(0x008, vstart), entry(0x009, vxsat.asUInt),
    entry(0x00a, vxrm), entry(0x00f, vxrm ## vxsat),
    entry(0xc20, vl), entry(0xc21, vtype), entry(0xc22, p.regBytes.U(64.W)))
  io.exists := reads.map { case (a, _) => io.address === a.U }.reduce(_ || _)
  io.read := Mux1H(reads.map { case (a, d) => (io.address === a.U) -> d })
  io.state.vl := vl; io.state.vtype := vtype; io.state.vstart := vstart; io.state.vxrm := vxrm
  io.state.enabled := vs =/= 0.U; io.vs := vs
  when(io.commit.valid) {
    vs := 3.U; vstart := 0.U
    when(io.commit.set) { vl := io.commit.vl; vtype := io.commit.vtype }
  }
  when(io.commit.vxsat) { vxsat := true.B; vs := 3.U }
  when(io.commit.trap) { vstart := io.commit.vstart; vs := 3.U }
  when(io.write) {
    when(io.writeAddress === 0x300.U || io.writeAddress === 0x100.U) { vs := io.writeData(10, 9) }
    when(io.writeAddress === 0x008.U) { vstart := io.writeData(p.lengthBits - 1, 0); vs := 3.U }
    when(io.writeAddress === 0x009.U) { vxsat := io.writeData(0); vs := 3.U }
    when(io.writeAddress === 0x00a.U) { vxrm := io.writeData(1, 0); vs := 3.U }
    when(io.writeAddress === 0x00f.U) { vxrm := io.writeData(2, 1); vxsat := io.writeData(0); vs := 3.U }
  }
}

/** Deterministic legal AVL choice: min(AVL,VLMAX). Invalid types produce vill
  * and zero vl; x0/x0 keeps vl only for an unchanged VLMAX.
  */
object RvvSetVl {
  def apply(instruction: UInt, rs1: UInt, rs2: UInt, oldVl: UInt, oldType: UInt, p: RvvParams): (UInt, UInt) = {
    val ivli = instruction(31, 30) === 3.U
    val registerType = instruction(31, 25) === 0x40.U
    val requestedType = Mux(registerType, rs2,
      Mux(ivli, instruction(29, 20), instruction(30, 20)).pad(64))
    def maximum(t: UInt): UInt = {
      val base = (p.regBytes.U(p.lengthBits.W) >> t(5, 3))
      Mux(t(2), base >> (8.U - t(2, 0)), (base << t(1, 0))(p.lengthBits - 1, 0))
    }
    val maxVl = maximum(requestedType)
    val keep = !ivli && instruction(19, 15) === 0.U && instruction(11, 7) === 0.U
    val invalid = requestedType(63, 8).orR || requestedType(5, 3) > 3.U ||
      requestedType(2, 0) === 4.U || maxVl === 0.U || (keep && maximum(oldType) =/= maxVl)
    val avl = Mux(ivli, instruction(19, 15).pad(64),
      Mux(instruction(19, 15) === 0.U, Mux(keep, oldVl.pad(64), maxVl.pad(64)), rs1))
    val newVl = Mux(invalid, 0.U, Mux(avl < maxVl, avl, maxVl))(p.lengthBits - 1, 0)
    val newType = Mux(invalid, (BigInt(1) << 63).U(64.W), requestedType(7, 0).pad(64))
    (newVl, newType)
  }
}

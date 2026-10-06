package flow.bus

import chisel3._
import chisel3.util._

/** One complete MMIO transaction at a time. Ownership spans independent
  * AW/W handshakes and lasts until B or R is consumed by that same hart.
  */
class Axi4LiteArbiter(n: Int, addrBits: Int = 32, dataBits: Int = 64) extends Module {
  require(n >= 1)
  val io = IO(new Bundle {
    val clients = Vec(n, Flipped(new Axi4LiteMasterIO(addrBits,dataBits)))
    val out = new Axi4LiteMasterIO(addrBits,dataBits)
  })
  val token = Module(new RRArbiter(Bool(), n))
  for(i <- 0 until n) {
    token.io.in(i).valid := io.clients(i).ar.valid || io.clients(i).aw.valid || io.clients(i).w.valid
    token.io.in(i).bits := io.clients(i).aw.valid || io.clients(i).w.valid
  }
  val active = RegInit(false.B)
  val owner = Reg(UInt((log2Ceil(n) max 1).W))
  val write = Reg(Bool())
  val addressDone = RegInit(false.B)
  val dataDone = RegInit(false.B)
  token.io.out.ready := !active
  when(token.io.out.fire) {
    active := true.B; owner := token.io.chosen; write := token.io.out.bits
    addressDone := false.B; dataDone := false.B
  }
  for(i <- 0 until n) {
    io.clients(i).ar.ready := false.B; io.clients(i).aw.ready := false.B; io.clients(i).w.ready := false.B
    io.clients(i).r.valid := false.B; io.clients(i).r.bits := io.out.r.bits
    io.clients(i).b.valid := false.B; io.clients(i).b.bits := io.out.b.bits
  }
  val selected = if(n == 1) io.clients(0) else io.clients(owner)
  io.out.ar.valid := active && !write && !addressDone && selected.ar.valid
  io.out.ar.bits := selected.ar.bits
  io.out.aw.valid := active && write && !addressDone && selected.aw.valid
  io.out.aw.bits := selected.aw.bits
  io.out.w.valid := active && write && !dataDone && selected.w.valid
  io.out.w.bits := selected.w.bits
  selected.ar.ready := active && !write && !addressDone && io.out.ar.ready
  selected.aw.ready := active && write && !addressDone && io.out.aw.ready
  selected.w.ready := active && write && !dataDone && io.out.w.ready
  when(io.out.ar.fire || io.out.aw.fire) { addressDone := true.B }
  when(io.out.w.fire) { dataDone := true.B }
  val readIssued = addressDone || io.out.ar.fire
  val writeIssued = (addressDone || io.out.aw.fire) && (dataDone || io.out.w.fire)
  selected.r.valid := active && !write && readIssued && io.out.r.valid
  selected.b.valid := active && write && writeIssued && io.out.b.valid
  io.out.r.ready := active && !write && readIssued && selected.r.ready
  io.out.b.ready := active && write && writeIssued && selected.b.ready
  when(io.out.r.fire || io.out.b.fire) { active := false.B }
}

package flow.bus

import chisel3._
import chisel3.util._

/** Minimal AXI4 / AXI4-Lite master-side bundles (no repo definition existed). */
case class Axi4Params(addrBits: Int, dataBits: Int, idBits: Int)

class Axi4Ar(p: Axi4Params) extends Bundle {
  val id = UInt(p.idBits.W); val addr = UInt(p.addrBits.W)
  val len = UInt(8.W); val size = UInt(3.W); val burst = UInt(2.W); val prot = UInt(3.W)
}
class Axi4Aw(p: Axi4Params) extends Axi4Ar(p)
class Axi4W(p: Axi4Params) extends Bundle {
  val data = UInt(p.dataBits.W); val strb = UInt((p.dataBits / 8).W); val last = Bool()
}
class Axi4R(p: Axi4Params) extends Bundle {
  val id = UInt(p.idBits.W); val data = UInt(p.dataBits.W); val resp = UInt(2.W); val last = Bool()
}
class Axi4B(p: Axi4Params) extends Bundle { val id = UInt(p.idBits.W); val resp = UInt(2.W) }

class Axi4MasterIO(p: Axi4Params) extends Bundle {
  val ar = Decoupled(new Axi4Ar(p))
  val r = Flipped(Decoupled(new Axi4R(p)))
  val aw = Decoupled(new Axi4Aw(p))
  val w = Decoupled(new Axi4W(p))
  val b = Flipped(Decoupled(new Axi4B(p)))
}

class Axi4LiteAddr(addrBits: Int) extends Bundle { val addr = UInt(addrBits.W); val prot = UInt(3.W) }
class Axi4LiteW(dataBits: Int) extends Bundle { val data = UInt(dataBits.W); val strb = UInt((dataBits / 8).W) }
class Axi4LiteR(dataBits: Int) extends Bundle { val data = UInt(dataBits.W); val resp = UInt(2.W) }

class Axi4LiteMasterIO(addrBits: Int, dataBits: Int) extends Bundle {
  val ar = Decoupled(new Axi4LiteAddr(addrBits))
  val r = Flipped(Decoupled(new Axi4LiteR(dataBits)))
  val aw = Decoupled(new Axi4LiteAddr(addrBits))
  val w = Decoupled(new Axi4LiteW(dataBits))
  val b = Flipped(Decoupled(UInt(2.W)))
}

object Axi4 {
  val BurstIncr: UInt = 1.U(2.W)
  val RespOkay: UInt = 0.U(2.W)
}

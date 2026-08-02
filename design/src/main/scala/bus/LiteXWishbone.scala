package flow.bus

import chisel3._
import chisel3.util.log2Ceil

/** Parameters that affect the physical LiteX Wishbone connection. */
final case class LiteXWishboneParameters(
    byteAddressWidth: Int = 32,
    dataWidth: Int = 64
) {
  require(byteAddressWidth > 0, "Wishbone byte address width must be positive")
  require(dataWidth >= 8 && (dataWidth & (dataWidth - 1)) == 0,
    "Wishbone data width must be a power of two and at least one byte")
  require(dataWidth % 8 == 0, "Wishbone data width must contain whole bytes")

  val dataBytes: Int = dataWidth / 8
  val byteOffsetWidth: Int = log2Ceil(dataBytes)
  val wordAddressWidth: Int = byteAddressWidth - byteOffsetWidth

  require(wordAddressWidth > 0, "Wishbone word address width must be positive")
}

/** Standard LiteX-facing Wishbone master signals.
  *
  * `adr` is a data-word address, as expected by LiteX Wishbone. Core-side
  * physical addresses remain byte addressed and are shifted by the bridge.
  */
class LiteXWishboneMasterIO(val params: LiteXWishboneParameters) extends Bundle {
  val cyc = Output(Bool())
  val stb = Output(Bool())
  val we = Output(Bool())
  val adr = Output(UInt(params.wordAddressWidth.W))
  val dat_w = Output(UInt(params.dataWidth.W))
  val sel = Output(UInt(params.dataBytes.W))
  val cti = Output(UInt(3.W))
  val bte = Output(UInt(2.W))

  val ack = Input(Bool())
  val err = Input(Bool())
  val dat_r = Input(UInt(params.dataWidth.W))
}

object WishboneCycleType {
  val Classic: UInt = "b000".U(3.W)
  val IncrementingBurst: UInt = "b010".U(3.W)
  val EndOfBurst: UInt = "b111".U(3.W)
}

object WishboneBurstType {
  val Linear: UInt = "b00".U(2.W)
}

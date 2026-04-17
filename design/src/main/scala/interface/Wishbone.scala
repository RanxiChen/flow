package flow.interface

import chisel3._

class LiteXWishboneMaster(val addrWidth: Int = 32, val dataWidth: Int = 64) extends Bundle {
  private val byteCount = dataWidth / 8
  private val adrShift  = chisel3.util.log2Ceil(byteCount)

  require(dataWidth % 8 == 0, "Wishbone data width must be byte aligned")
  require(addrWidth >= adrShift, "Address width is too small for the selected data width")

  val adr   = Output(UInt((addrWidth - adrShift).W))
  val dat_w = Output(UInt(dataWidth.W))
  val dat_r = Input(UInt(dataWidth.W))
  val sel   = Output(UInt(byteCount.W))
  val cyc   = Output(Bool())
  val stb   = Output(Bool())
  val ack   = Input(Bool())
  val we    = Output(Bool())
  val cti   = Output(UInt(3.W))
  val bte   = Output(UInt(2.W))
  val err   = Input(Bool())
}

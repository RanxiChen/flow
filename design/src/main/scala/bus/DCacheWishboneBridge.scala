package flow.bus

import chisel3._
import chisel3.util._
import flow.interface.{DCacheMemReqIO, DCacheMemRespIO}

object DCacheWishboneBridgeState extends ChiselEnum {
  val Idle, TransferBeat, Respond = Value
}

/** Converts blocking DCache line/scalar pulses into LiteX Wishbone cycles.
  *
  * Line transactions are split into consecutive 64-bit Wishbone beats. Scalar
  * transactions always occupy one aligned 64-bit beat and use byte select.
  * Every address/control/payload field is held stable until ack or err.
  */
class DCacheWishboneBridge(
    val physicalAddressWidth: Int = 32,
    val lineBytes: Int = 32,
    val busDataWidth: Int = 64
) extends Module {
  private val wbParams = LiteXWishboneParameters(
    byteAddressWidth = physicalAddressWidth,
    dataWidth = busDataWidth
  )
  private val beatBytes = wbParams.dataBytes
  private val beatCount = lineBytes / beatBytes
  private val beatIndexWidth = math.max(1, log2Ceil(beatCount))
  private val beatDataShift = log2Ceil(busDataWidth)
  private val beatMaskShift = log2Ceil(beatBytes)
  private val lineWidth = lineBytes * 8

  require(physicalAddressWidth > wbParams.byteOffsetWidth && physicalAddressWidth <= 64,
    "DCache bridge physical address width must fit the 64-bit core address")
  require(lineBytes > 0 && (lineBytes & (lineBytes - 1)) == 0,
    "DCache line size must be a positive power of two")
  require(lineBytes >= beatBytes && lineBytes % beatBytes == 0,
    "DCache line must contain complete Wishbone beats")

  val io = IO(new Bundle {
    val cacheReq = Flipped(new DCacheMemReqIO(64, lineBytes))
    val cacheResp = Flipped(new DCacheMemRespIO(lineBytes))
    val wishbone = new LiteXWishboneMasterIO(wbParams)
  })

  import DCacheWishboneBridgeState._

  val state = RegInit(Idle)
  val requestAddress = RegInit(0.U(64.W))
  val requestIsWrite = RegInit(false.B)
  val requestIsLine = RegInit(false.B)
  val requestData = RegInit(0.U(lineWidth.W))
  val requestMask = RegInit(0.U(lineBytes.W))
  val beatIndex = RegInit(0.U(beatIndexWidth.W))
  val readBeats = Reg(Vec(beatCount, UInt(busDataWidth.W)))
  val responseError = RegInit(false.B)

  io.cacheResp.vld := state === Respond
  io.cacheResp.data := Mux(responseError, 0.U, readBeats.asUInt)
  io.cacheResp.error := responseError

  io.wishbone.cyc := false.B
  io.wishbone.stb := false.B
  io.wishbone.we := requestIsWrite
  io.wishbone.adr := 0.U
  io.wishbone.dat_w := 0.U
  io.wishbone.sel := 0.U
  io.wishbone.cti := WishboneCycleType.Classic
  io.wishbone.bte := WishboneBurstType.Linear

  val requestAddressInRange = if (physicalAddressWidth == 64) {
    true.B
  } else {
    !io.cacheReq.addr(63, physicalAddressWidth).orR
  }
  val requestLineAligned = !io.cacheReq.addr(log2Ceil(lineBytes) - 1, 0).orR
  val requestBeatAligned = !io.cacheReq.addr(wbParams.byteOffsetWidth - 1, 0).orR

  when(io.cacheReq.req) {
    assert(state === Idle,
      "DCache memory request pulse arrived while its dedicated bridge was busy")
  }

  switch(state) {
    is(Idle) {
      when(io.cacheReq.req) {
        requestAddress := io.cacheReq.addr
        requestIsWrite := io.cacheReq.isWrite
        requestIsLine := io.cacheReq.isLine
        requestData := io.cacheReq.data
        requestMask := io.cacheReq.mask
        beatIndex := 0.U
        responseError := false.B

        when(!requestAddressInRange ||
            Mux(io.cacheReq.isLine, !requestLineAligned, !requestBeatAligned)) {
          responseError := true.B
          state := Respond
        }.otherwise {
          state := TransferBeat
        }
      }
    }

    is(TransferBeat) {
      val wordBase = requestAddress(
        physicalAddressWidth - 1,
        wbParams.byteOffsetWidth
      )
      val dataShift = beatIndex << beatDataShift
      val maskShift = beatIndex << beatMaskShift
      val currentData = (requestData >> dataShift)(busDataWidth - 1, 0)
      val currentMask = (requestMask >> maskShift)(beatBytes - 1, 0)
      val lastBeat = Mux(requestIsLine, beatIndex === (beatCount - 1).U, true.B)

      io.wishbone.cyc := true.B
      io.wishbone.stb := true.B
      io.wishbone.we := requestIsWrite
      io.wishbone.adr := wordBase + beatIndex
      io.wishbone.dat_w := currentData
      io.wishbone.sel := currentMask

      when(io.wishbone.err) {
        responseError := true.B
        state := Respond
      }.elsewhen(io.wishbone.ack) {
        when(!requestIsWrite) {
          readBeats(beatIndex) := io.wishbone.dat_r
        }

        when(lastBeat) {
          responseError := false.B
          state := Respond
        }.otherwise {
          beatIndex := beatIndex + 1.U
        }
      }
    }

    is(Respond) {
      state := Idle
    }
  }
}

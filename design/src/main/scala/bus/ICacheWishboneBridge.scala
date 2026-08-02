package flow.bus

import chisel3._
import chisel3.util._
import flow.interface.{L1CacheMissReqIO, L1CacheMissRespIO}

object ICacheWishboneBridgeState extends ChiselEnum {
  val Idle, ReadBeat, Respond = Value
}

/** Converts the ICache's read-only cache-line pulse protocol to LiteX Wishbone.
  *
  * The ICache emits exactly one request pulse for a blocking miss. This bridge
  * must therefore be idle and unconditionally capture that pulse. It then reads
  * one complete cache line over consecutive Wishbone data beats and emits one
  * response pulse containing the assembled line.
  */
class ICacheWishboneBridge(
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
  private val lineWidth = lineBytes * 8

  require(lineBytes > 0 && (lineBytes & (lineBytes - 1)) == 0,
    "ICache line size must be a positive power of two")
  require(physicalAddressWidth > wbParams.byteOffsetWidth && physicalAddressWidth <= 64,
    "ICache bridge physical address width must fit the 64-bit core address")
  require(lineBytes >= beatBytes,
    "ICache line must contain at least one complete Wishbone beat")
  require(lineBytes % beatBytes == 0,
    "ICache line size must be an integer number of Wishbone beats")
  require(beatCount > 0,
    "ICache refill must contain at least one Wishbone beat")

  val io = IO(new Bundle {
    val cacheReq = Flipped(new L1CacheMissReqIO(64))
    val cacheResp = Flipped(new L1CacheMissRespIO(lineWidth))
    val wishbone = new LiteXWishboneMasterIO(wbParams)
  })

  import ICacheWishboneBridgeState._

  val state = RegInit(Idle)
  val baseAddress = RegInit(0.U(64.W))
  val beatIndex = RegInit(0.U(beatIndexWidth.W))
  val lineBeats = Reg(Vec(beatCount, UInt(busDataWidth.W)))
  val responseError = RegInit(false.B)

  io.cacheResp.data := Mux(responseError, 0.U, lineBeats.asUInt)
  io.cacheResp.vld := false.B
  io.cacheResp.error := responseError

  io.wishbone.cyc := false.B
  io.wishbone.stb := false.B
  io.wishbone.we := false.B
  io.wishbone.adr := 0.U
  io.wishbone.dat_w := 0.U
  io.wishbone.sel := Fill(wbParams.dataBytes, 1.U(1.W))
  io.wishbone.cti := WishboneCycleType.Classic
  io.wishbone.bte := WishboneBurstType.Linear

  val requestAddressInRange = if (physicalAddressWidth == 64) {
    true.B
  } else {
    !io.cacheReq.paddr(63, physicalAddressWidth).orR
  }
  val requestLineAligned =
    !io.cacheReq.paddr(log2Ceil(lineBytes) - 1, 0).orR

  // The pulse protocol deliberately has no ready signal. A dedicated lower
  // level must always be able to capture a new request when it is emitted.
  when(io.cacheReq.req) {
    assert(state === Idle,
      "ICache refill request pulse arrived while its dedicated bridge was busy")
  }

  switch(state) {
    is(Idle) {
      when(io.cacheReq.req) {
        baseAddress := io.cacheReq.paddr
        beatIndex := 0.U
        responseError := false.B

        when(!requestAddressInRange || !requestLineAligned) {
          responseError := true.B
          state := Respond
        }.otherwise {
          state := ReadBeat
        }
      }
    }

    is(ReadBeat) {
      val wordBase = baseAddress(
        physicalAddressWidth - 1,
        wbParams.byteOffsetWidth
      )

      io.wishbone.cyc := true.B
      io.wishbone.stb := true.B
      io.wishbone.adr := wordBase + beatIndex
      io.wishbone.cti := Mux(
        beatIndex === (beatCount - 1).U,
        WishboneCycleType.EndOfBurst,
        WishboneCycleType.IncrementingBurst
      )

      when(io.wishbone.err) {
        responseError := true.B
        state := Respond
      }.elsewhen(io.wishbone.ack) {
        lineBeats(beatIndex) := io.wishbone.dat_r

        when(beatIndex === (beatCount - 1).U) {
          responseError := false.B
          state := Respond
        }.otherwise {
          beatIndex := beatIndex + 1.U
        }
      }
    }

    is(Respond) {
      io.cacheResp.vld := true.B
      state := Idle
    }
  }
}

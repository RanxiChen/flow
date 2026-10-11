package flow.bus

import chisel3._
import chisel3.util._
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.platform.{PMAChecker, PMAAccessType}

/** A classic 64-bit LiteX DMA beat owns exactly one Home request/response.
  * The ACK follows coherent completion, including dirty-owner recall and
  * invalidation. No direct DDR exit or CPU permission context is used here.
  */
class CoherentDmaWishbone(g: BreezeMemGeometry) extends Module {
  private val p = CoherenceParams(g)
  private val wb = LiteXWishboneParameters(g.paddrBits, 64)
  val io = IO(new Bundle {
    val wishbone = Flipped(new LiteXWishboneMasterIO(wb))
    val home = new ReadClientIO(p)
  })
  val idle :: request :: response :: finish :: Nil = Enum(4)
  val state = RegInit(idle)
  val q = Reg(new CoherenceReq(p))
  val offset = Reg(UInt(log2Ceil(p.lineBytes / 8).W))
  val readData = Reg(UInt(64.W))
  val failed = RegInit(false.B)
  val abandoned = RegInit(false.B)
  val address = io.wishbone.adr ## 0.U(3.W)
  val pma = Module(new PMAChecker)
  pma.io.query.addr := address
  pma.io.query.sizeLog2 := 3.U
  pma.io.query.accessType := Mux(io.wishbone.we, PMAAccessType.Store, PMAAccessType.Load)
  val allowed = pma.io.result.allowed && pma.io.result.cacheable && !pma.io.result.device
  val shiftBytes = address(p.offBits - 1, 3) ## 0.U(3.W)
  when(state === idle && io.wishbone.cyc && io.wishbone.stb) {
    q.op := Mux(io.wishbone.we, ReqOp.MaskWrite, ReqOp.Read)
    q.addr := address(g.paddrBits - 1, p.offBits)
    q.id := 0.U
    q.mask := (io.wishbone.sel.pad(p.lineBytes) << shiftBytes)(p.lineBytes - 1, 0)
    q.data := (io.wishbone.dat_w.pad(p.lineBits) << (shiftBytes ## 0.U(3.W)))(p.lineBits - 1, 0)
    offset := address(p.offBits - 1, 3)
    failed := !allowed
    abandoned := false.B
    readData := 0.U
    state := Mux(!allowed || (io.wishbone.we && !io.wishbone.sel.orR), finish, request)
  }
  io.home.req.valid := state === request
  io.home.req.bits := q
  when(io.home.req.fire) { state := response }
  io.home.rspDown.ready := state === response
  when(io.home.rspDown.fire) {
    assert(io.home.rspDown.bits.id === 0.U, "DMA response owner changed")
    assert(io.home.rspDown.bits.op === Mux(q.op === ReqOp.Read, RspDownOp.ReadData, RspDownOp.WriteAck),
      "DMA response opcode does not match its request")
    readData := (io.home.rspDown.bits.data >> (offset ## 0.U(6.W)))(63, 0)
    failed := io.home.rspDown.bits.error
    state := finish
  }
  io.wishbone.dat_r := readData
  when(state =/= idle && !io.wishbone.cyc) { abandoned := true.B }
  io.wishbone.ack := state === finish && io.wishbone.cyc && !abandoned && !failed
  io.wishbone.err := state === finish && io.wishbone.cyc && !abandoned && failed
  when(state === finish) { state := idle }
  // Even an aborted bus cycle must drain its accepted Home response. A new
  // request cannot replace that owner; a normal master holds CYC through ACK.
  when(state =/= idle && state =/= finish) {
    assert(!io.wishbone.cyc || !io.wishbone.stb || io.wishbone.adr === (q.addr ## offset),
      "DMA master changed address before coherent completion")
  }
}

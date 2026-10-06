package flow.l1d

import chisel3._
import chisel3.util._
import flow.bus.{Axi4, Axi4LiteMasterIO}

/** Blocking MMIO state machine over AXI4-Lite (l1d-rtl-spec §9). */
class L1DMmio(p: L1DParams) extends Module {
  val io = IO(new Bundle {
    /** S2 found a device Load/Store and holds it (s2Hold). */
    val start = Flipped(Valid(new L1MmioReq(p)))
    /** Issue condition: request is oldest (in S2 = WB) and L1D drained. */
    val drained = Input(Bool())
    val s2Kill = Input(Bool())
    val busy = Output(Bool())       // mmioBusy: issued, not retired
    val active = Output(Bool())     // any non-Idle state (closes CPU entry)
    val done = Valid(new Bundle {
      val error = Bool()
      val rdata = UInt(64.W)        // raw beat; S2 formatter applies §5.4
    })
    val axi = new Axi4LiteMasterIO(p.paddrBits, 64)
  })

  val st = RegInit(MmioState.Idle)
  val r = Reg(new L1MmioReq(p))
  val awDone = Reg(Bool())
  val wDone = Reg(Bool())

  when(st === MmioState.Idle && io.start.valid) {
    r := io.start.bits
    awDone := false.B
    wDone := false.B
    st := MmioState.WaitOldest
  }
  when(st === MmioState.WaitOldest) {
    when(io.s2Kill) { st := MmioState.Idle }
      .elsewhen(io.drained) { st := MmioState.Issue }
  }

  private val shift = r.paddr(2, 0) ## 0.U(3.W)
  private val bytes = (1.U(4.W) << r.size)(3, 0)
  private val strb = (((1.U(9.W) << bytes) - 1.U)(7, 0) << r.paddr(2, 0))(7, 0)

  io.axi.ar.valid := st === MmioState.Issue && !r.isWrite
  io.axi.ar.bits.addr := r.paddr
  io.axi.ar.bits.prot := 0.U
  io.axi.aw.valid := st === MmioState.Issue && r.isWrite && !awDone
  io.axi.aw.bits.addr := r.paddr
  io.axi.aw.bits.prot := 0.U
  io.axi.w.valid := st === MmioState.Issue && r.isWrite && !wDone
  io.axi.w.bits.data := (r.wdata << shift)(63, 0)
  io.axi.w.bits.strb := strb
  when(io.axi.aw.fire) { awDone := true.B }
  when(io.axi.w.fire) { wDone := true.B }
  when(io.axi.ar.fire || (r.isWrite && (awDone || io.axi.aw.fire) && (wDone || io.axi.w.fire))) {
    when(st === MmioState.Issue) { st := MmioState.Resp }
  }

  io.axi.r.ready := st === MmioState.Resp
  io.axi.b.ready := st === MmioState.Resp
  io.done.valid := io.axi.r.fire || io.axi.b.fire
  io.done.bits.error := Mux(r.isWrite, io.axi.b.bits =/= Axi4.RespOkay, io.axi.r.bits.resp =/= Axi4.RespOkay)
  io.done.bits.rdata := io.axi.r.bits.data
  when(io.done.valid) { st := MmioState.Idle }

  io.busy := st === MmioState.Issue || st === MmioState.Resp
  io.active := st =/= MmioState.Idle
}

package flow.mmu

import chisel3._
import chisel3.util._
import flow.core.PRIV_MODE
import flow.interface._

/** First-match PMP checker for 16 RV64 entries (TOR, NA4 and NAPOT). */
class BreezePmpChecker(val xlen: Int = 64) extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(xlen.W))
    val sizeLog2 = Input(UInt(3.W))
    val access = Input(BreezeMmuAccess())
    val privilege = Input(UInt(PRIV_MODE.width.W))
    val context = Input(new BreezeMmuContext(xlen))
    val allowed = Output(Bool())
  })

  val accessStart = Cat(0.U(1.W), io.addr)
  val accessEnd = accessStart + (1.U(65.W) << io.sizeLog2)
  val overlaps = Wire(Vec(16, Bool()))
  val contains = Wire(Vec(16, Bool()))
  val permissions = Wire(Vec(16, Bool()))

  for (n <- 0 until 16) {
    val cfg = io.context.pmpcfg(n)
    val a = cfg(4, 3)
    val encoded = io.context.pmpaddr(n)
    val napotMask = encoded ^ (encoded + 1.U)
    val torLower = if (n == 0) 0.U(65.W)
      else Cat(0.U(9.W), io.context.pmpaddr(n - 1), 0.U(2.W))
    val torUpper = Cat(0.U(9.W), encoded, 0.U(2.W))
    val naBase = Cat(0.U(9.W), encoded, 0.U(2.W))
    val napotBase = Cat(0.U(9.W), encoded & ~napotMask, 0.U(2.W))
    val napotTop = Cat(0.U(9.W), encoded | napotMask, 0.U(2.W)) + 4.U
    val lower = MuxLookup(a, 0.U(65.W))(Seq(
      1.U -> torLower, 2.U -> naBase, 3.U -> napotBase))
    val upper = MuxLookup(a, 0.U(65.W))(Seq(
      1.U -> torUpper, 2.U -> (naBase + 4.U), 3.U -> napotTop))
    overlaps(n) := a =/= 0.U && accessStart < upper && accessEnd > lower
    contains(n) := accessStart >= lower && accessEnd <= upper
    val rwx = MuxLookup(io.access, false.B)(Seq(
      BreezeMmuAccess.Fetch -> cfg(2),
      BreezeMmuAccess.Load -> cfg(0),
      BreezeMmuAccess.Store -> cfg(1)))
    permissions(n) := contains(n) &&
      Mux(io.privilege === PRIV_MODE.M.U && !cfg(7), true.B, rwx)
  }

  val anyMatch = overlaps.asUInt.orR
  val selected = PriorityEncoder(overlaps.asUInt)
  io.allowed := Mux(anyMatch, permissions(selected), io.privilege === PRIV_MODE.M.U)
}

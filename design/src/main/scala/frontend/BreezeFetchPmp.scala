package flow.frontend

import chisel3._
import chisel3.util._
import flow.config.BreezePmpConfig
import flow.interface._

/** Registered F0 low comparisons, concurrent F1 per-candidate high compares.
  * Physical fetch operations are 2-byte parcels and never cross a 4 KiB page.
  * CSRFile supplies registered legal PMP ranges; configuration changes must
  * cancel in-flight queries. No permission cache is embedded in the TLB.
  */
class BreezeFetchPmp(candidates: Int) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())
    val vaddr = Input(UInt(64.W))
    val context = Input(new BreezeMmuContext(64))
    val paddrs = Input(Vec(candidates, UInt(64.W)))
    val select = Input(UInt(candidates.W))
    val allowed = Output(Vec(2, Bool()))
  })
  val n = BreezePmpConfig.ActiveEntries
  val lowerStart = Reg(Vec(2, Vec(n, Bool())))
  val upperStart = Reg(Vec(2, Vec(n, Bool())))
  val lowerEnd = Reg(Vec(2, Vec(n, Bool())))
  val upperEnd = Reg(Vec(2, Vec(n, Bool())))
  val xAllowed = Reg(Vec(n, Bool()))
  val machine = Reg(Bool())
  when(io.fire) {
    assert(io.context.pmpRangesValid, "fetch requires CSR-predecoded PMP ranges")
    assert(!io.vaddr(0), "IALIGN=16")
    machine := io.context.privilege === 3.U
    for (e <- 0 until n) {
      xAllowed(e) := (io.context.privilege === 3.U && !io.context.pmpcfg(e)(7)) || io.context.pmpcfg(e)(2)
      for (slot <- 0 until 2) {
        val low = (io.vaddr(11, 0) + (slot * 2).U)(11, 0)
        val last = low | 1.U // aligned 2-byte operation, no wide end adder
        val range = io.context.pmpRanges(e)
        lowerStart(slot)(e) := low < range.lower(11, 0)
        upperStart(slot)(e) := low < range.upper(11, 0)
        lowerEnd(slot)(e) := last < range.lower(11, 0)
        upperEnd(slot)(e) := last < range.upper(11, 0)
      }
    }
  }
  // Lexicographic comparisons use a balanced tree of small chunks.
  def compare(a: UInt, b: UInt): (Bool, Bool) = {
    val chunks = (0 until 7).reverse.map { i =>
      val hi = math.min(52, i * 8 + 7)
      val aa = a(hi, i * 8); val bb = b(hi, i * 8)
      (aa < bb, aa === bb)
    }
    def merge(xs: Seq[(Bool, Bool)]): (Bool, Bool) = {
      if (xs.size == 1) xs.head else {
        val (lt0, eq0) = merge(xs.take(xs.size / 2))
        val (lt1, eq1) = merge(xs.drop(xs.size / 2))
        (lt0 || (eq0 && lt1), eq0 && eq1)
      }
    }
    merge(chunks)
  }
  val loLt = Wire(Vec(n, Bool())); val loEq = Wire(Vec(n, Bool()))
  val hiLt = Wire(Vec(n, Bool())); val hiEq = Wire(Vec(n, Bool()))
  for (e <- 0 until n) {
    val r = io.context.pmpRanges(e)
    val lows = io.paddrs.map(pa => compare(Cat(0.U(1.W), pa(63, 12)), r.lower(64, 12)))
    val highs = io.paddrs.map(pa => compare(Cat(0.U(1.W), pa(63, 12)), r.upper(64, 12)))
    loLt(e) := Mux1H(io.select.asBools, lows.map(_._1))
    loEq(e) := Mux1H(io.select.asBools, lows.map(_._2))
    hiLt(e) := Mux1H(io.select.asBools, highs.map(_._1))
    hiEq(e) := Mux1H(io.select.asBools, highs.map(_._2))
  }
  for (slot <- 0 until 2) {
    val overlaps = Wire(Vec(n, Bool())); val permits = Wire(Vec(n, Bool()))
    for (e <- 0 until n) {
      val startBelowLower = loLt(e) || (loEq(e) && lowerStart(slot)(e))
      val startBelowUpper = hiLt(e) || (hiEq(e) && upperStart(slot)(e))
      val endBelowLower = loLt(e) || (loEq(e) && lowerEnd(slot)(e))
      val endBelowUpper = hiLt(e) || (hiEq(e) && upperEnd(slot)(e))
      overlaps(e) := io.context.pmpRanges(e).nonempty && startBelowUpper && !endBelowLower
      permits(e) := !startBelowLower && endBelowUpper && xAllowed(e)
    }
    val winner = PriorityEncoderOH(overlaps.asUInt)
    io.allowed(slot) := (winner & permits.asUInt).orR || (!overlaps.asUInt.orR && machine)
  }
}

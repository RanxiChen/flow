package flow.mmu.sv39

import chisel3._
import chisel3.util._

class Sv39WalkCache(sets: Int, ways: Int, keyBits: Int) extends Module {
  Seq(sets, ways).foreach(n => require(n >= 1 && (n & (n - 1)) == 0))
  private val indexBits = log2Ceil(sets)
  require(keyBits >= indexBits)
  val io = IO(new Bundle { val port = new WalkCachePort(keyBits); val flush = Input(Bool()) })
  class Entry extends Bundle {
    val valid = Bool(); val tag = UInt((keyBits - indexBits).W)
    val asid = UInt(16.W); val ppn = UInt(44.W)
  }
  val entries = RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.fill(ways)(0.U.asTypeOf(new Entry))))))
  val plru = RegInit(VecInit(Seq.fill(sets)(0.U((ways - 1).W))))
  def index(key: UInt): UInt = if (sets == 1) 0.U else key(indexBits - 1, 0)
  def tag(key: UInt): UInt = key >> indexBits
  def matches(key: UInt, asid: UInt): Vec[Bool] = VecInit(entries(index(key)).map(e =>
    e.valid && e.tag === tag(key) && e.asid === asid))
  val hits = matches(io.port.lookup.key, io.port.lookup.asid)
  io.port.lookup.hit := hits.asUInt.orR
  io.port.lookup.ppn := Mux1H(hits, entries(index(io.port.lookup.key)).map(_.ppn))
  when(io.port.lookupFire && io.port.lookup.hit) {
    val s = index(io.port.lookup.key)
    plru(s) := TreePlru.touch(plru(s), PriorityEncoder(hits), ways)
  }
  when(io.port.fill.valid) {
    assert(!matches(io.port.fill.bits.key, io.port.fill.bits.asid).asUInt.orR, "walk-cache duplicate fill")
    val s = index(io.port.fill.bits.key)
    val invalid = VecInit(entries(s).map(e => !e.valid))
    val victim = Mux(invalid.asUInt.orR, PriorityEncoder(invalid), TreePlru.victim(plru(s), ways))
    val target = if (ways == 1) entries(s)(0) else entries(s)(victim)
    target.valid := true.B
    target.tag := tag(io.port.fill.bits.key)
    target.asid := io.port.fill.bits.asid
    target.ppn := io.port.fill.bits.ppn
    plru(s) := TreePlru.touch(plru(s), victim, ways)
  }
  when(io.flush) { for (s <- 0 until sets; w <- 0 until ways) entries(s)(w).valid := false.B }
}

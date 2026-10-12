package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.rvv.RvvAge

/** Dependency ownership starts on acceptance, before commit or execution.
  * Every unit has a registered issue mailbox. No ready feedback passes through
  * the age/dependency comparisons. Units complete only after physical writes. */
class ZveWindow(p: ZveParams) extends Module {
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new ZveCommand(p)))
    val verdict = Decoupled(new ZveVerdict(p))
    val commit = Input(Bool()); val kill = Input(Bool()); val serialGo = Input(Bool())
    val execute = Vec(p.units, Decoupled(new ZveCommand(p)))
    val complete = Vec(p.units, Flipped(Decoupled(new ZveCompletion(p))))
    val completion = Decoupled(new ZveCompletion(p))
    val drained = Output(Bool()); val occupancy = Output(UInt(log2Ceil(p.window + 1).W))
  })
  val live = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val committed = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val judged = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val issued = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val executed = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val commands = Reg(Vec(p.window, new ZveCommand(p)))
  val nextAge = RegInit(0.U(p.ageBits.W))
  val ageFits = (0 until p.window).map(i => !live(i) ||
    (nextAge - commands(i).age) < p.vrf.ageLimit.U).reduce(_ && _)
  val free = PriorityEncoder(~live.asUInt)
  io.issue.ready := !live.asUInt.andR && ageFits && !io.kill
  io.occupancy := PopCount(live)
  def oldest(eligible: Seq[Bool]): UInt = {
    val wins = (0 until p.window).map(i => eligible(i) &&
      (0 until p.window).filter(_ != i).map(j => !eligible(j) ||
        RvvAge.older(commands(i).age, commands(j).age)).reduce(_ && _))
    PriorityEncoder(VecInit(wins).asUInt)
  }
  val pending = (0 until p.window).map(i => live(i) && !committed(i))
  val oldestPending = oldest(pending)
  val unjudged = (0 until p.window).map(i => pending(i) && !judged(i))
  val judgement = oldest(unjudged)
  io.verdict.valid := unjudged.reduce(_ || _) && !io.kill
  io.verdict.bits.slot := judgement; io.verdict.bits.age := commands(judgement).age
  io.verdict.bits.kind := Mux(!commands(judgement).legal, 2.U,
    Mux(commands(judgement).serial, 3.U, 0.U))
  io.verdict.bits.cause := Mux(commands(judgement).legal, 0.U, 2.U)
  io.verdict.bits.tval := commands(judgement).issue.scalar.instruction
  when(io.verdict.fire) { judged(judgement) := true.B }

  // Each completion already includes all result/state ownership. The queue
  // accepts it before dependency ownership is released, never discards flags.
  val returns = Module(new RRArbiter(new ZveCompletion(p), p.units))
  returns.io.in <> io.complete
  val completed = Module(new Queue(new ZveCompletion(p), p.window, pipe=false, flow=false))
  completed.io.enq <> returns.io.out
  io.completion <> completed.io.deq
  when(returns.io.out.fire) {
    val r = returns.io.out.bits
    assert(live(r.slot) && committed(r.slot) && executed(r.slot), "completion lacks executed ownership")
    assert(commands(r.slot).age === r.age, "stale completion reused a window slot")
    assert(commands(r.slot).unit === returns.io.chosen, "completion from the wrong unit")
    live(r.slot) := false.B
  }

  val mailboxValid = RegInit(VecInit(Seq.fill(p.units)(false.B)))
  val mailbox = Reg(Vec(p.units, new ZveCommand(p)))
  for(u <- 0 until p.units) {
    io.execute(u).valid := mailboxValid(u)
    io.execute(u).bits := mailbox(u)
    when(io.execute(u).fire) { mailboxValid(u) := false.B; executed(mailbox(u).slot) := true.B }
    val inUnit = (0 until p.window).map(i => live(i) && committed(i) &&
      !issued(i) && commands(i).unit === u.U)
    val selected = oldest(inUnit)
    val c = commands(selected)
    val conflicts = (0 until p.window).map(i => live(i) && i.U =/= selected &&
      RvvAge.older(commands(i).age, c.age) &&
      (((c.reads & commands(i).writes) | (c.writes & commands(i).reads) |
        (c.writes & commands(i).writes)).orR))
    // A serial instruction is a barrier until final completion. It waits only
    // for older live instructions, never for its own occupancy or young work.
    val olderLive = (0 until p.window).map(i => live(i) && RvvAge.older(commands(i).age,c.age))
    val olderSerial = (0 until p.window).map(i => olderLive(i) && commands(i).serial)
    val ready = !conflicts.reduce(_ || _) && !olderSerial.reduce(_ || _) &&
      (!c.serial || !olderLive.reduce(_ || _))
    when(!mailboxValid(u) && inUnit.reduce(_ || _) && ready) {
      mailbox(u) := c; mailboxValid(u) := true.B; issued(selected) := true.B
    }
  }
  when(io.commit || io.serialGo) {
    val c = commands(oldestPending)
    assert(pending.reduce(_ || _) && judged(oldestPending), "commit before oldest verdict")
    assert(c.legal && (c.serial === io.serialGo), "wrong commit/serialGo ownership")
    assert(!(io.commit && io.serialGo), "two commits in one cycle")
    committed(oldestPending) := true.B
  }
  when(io.kill) {
    for(i <- 0 until p.window) {
      when(live(i) && !committed(i) &&
        !((io.commit || io.serialGo) && oldestPending === i.U)) { live(i) := false.B }
    }
  }
  when(io.issue.fire) {
    assert(io.issue.bits.unit < p.units.U)
    commands(free) := io.issue.bits
    commands(free).slot := free; commands(free).age := nextAge
    live(free) := true.B; committed(free) := false.B; judged(free) := false.B; issued(free) := false.B; executed(free) := false.B
    nextAge := nextAge + 1.U
  }
  io.drained := !live.asUInt.orR && !mailboxValid.asUInt.orR && !completed.io.deq.valid
}

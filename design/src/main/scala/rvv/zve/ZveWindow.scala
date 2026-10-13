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
    val serialActive = Output(Bool())
    val drained = Output(Bool()); val occupancy = Output(UInt(log2Ceil(p.window + 1).W))
    /** Any accepted, unfinished vector memory access / vector store. */
    val memoryLive = Output(Bool()); val storeLive = Output(Bool())
  })
  val live = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val committed = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val judged = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val issued = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val executed = RegInit(VecInit(Seq.fill(p.window)(false.B)))
  val commands = Reg(Vec(p.window, new ZveCommand(p)))
  val nextAge = RegInit(0.U(p.ageBits.W))
  val serialPending=RegInit(false.B)
  val serialAge=Reg(UInt(p.ageBits.W))
  val serialSlot=Reg(UInt(p.slotBits.W))
  io.serialActive := serialPending
  val ageFits = (0 until p.window).map(i => !live(i) ||
    (nextAge - commands(i).age) < p.vrf.ageLimit.U).reduce(_ && _)
  val free = PriorityEncoder(~live.asUInt)
  io.issue.ready := !live.asUInt.andR && ageFits && !io.kill
  io.occupancy := PopCount(live)
  // Age matrix: before(i)(j) = live slot i was allocated before slot j. Ages
  // are handed out in allocation order, so among live slots this equals
  // RvvAge.older(age(i), age(j)) but needs no age comparator. R07: the
  // comparator trees were the synthesis critical path and ~half the window.
  val before = RegInit(VecInit(Seq.fill(p.window)(VecInit(Seq.fill(p.window)(false.B)))))
  def oldestOH(eligible: Seq[Bool]): UInt = VecInit((0 until p.window).map(i => eligible(i) &&
    !(0 until p.window).filter(_ != i).map(j => eligible(j) && before(j)(i)).reduce(_ || _))).asUInt
  def oldest(eligible: Seq[Bool]): UInt = OHToUInt(oldestOH(eligible))
  /** Live slots allocated before the one-hot slot `oh`. */
  def olderThan(oh: UInt): Seq[Bool] = (0 until p.window).map(i =>
    live(i) && (0 until p.window).filter(_ != i).map(j => oh(j) && before(i)(j)).foldLeft(false.B)(_ || _))
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
  when(io.completion.fire && serialPending && io.completion.bits.age===serialAge &&
      io.completion.bits.slot===serialSlot) { serialPending := false.B }
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
    val selectedOH = oldestOH(inUnit)
    val selected = OHToUInt(selectedOH)
    val c = Mux1H(selectedOH, commands)
    // A serial instruction is a barrier until final completion. It waits only
    // for older live instructions, never for its own occupancy or young work.
    val olderLive = olderThan(selectedOH)
    val conflicts = (0 until p.window).map(i => olderLive(i) &&
      (((c.reads & commands(i).writes) | (c.writes & commands(i).reads) |
        (c.writes & commands(i).writes)).orR))
    val olderSerial = (0 until p.window).map(i => olderLive(i) && commands(i).serial)
    val ready = !conflicts.reduce(_ || _) && !olderSerial.reduce(_ || _) &&
      !(serialPending && RvvAge.older(serialAge,c.age)) &&
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
    assert(!serialPending,"younger commit before final Serial status acceptance")
    committed(oldestPending) := true.B
    when(io.serialGo) { serialPending := true.B; serialAge := c.age; serialSlot := oldestPending }
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
    for(j <- 0 until p.window) {
      before(free)(j) := false.B
      before(j)(free) := live(j) && j.U =/= free
    }
    live(free) := true.B; committed(free) := false.B; judged(free) := false.B; issued(free) := false.B; executed(free) := false.B
    nextAge := nextAge + 1.U
  }
  def isMemory(c: ZveCommand): Bool = c.unit === ZveUnit.memory.U || c.unit === ZveUnit.stream.U
  io.memoryLive := (0 until p.window).map(i => live(i) && isMemory(commands(i))).reduce(_ || _)
  io.storeLive := (0 until p.window).map(i => live(i) && isMemory(commands(i)) &&
    commands(i).issue.scalar.instruction(6,0) === 0x27.U).reduce(_ || _)
  io.drained := !live.asUInt.orR && !mailboxValid.asUInt.orR && !completed.io.deq.valid && !serialPending
}

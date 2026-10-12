package flow.frontend

import chisel3._
import chisel3.util._

case class BreezeFrontendPipelineParams(historyBits: Int = 10, phtBanks: Int = 4,
    btbSets: Int = 32, btbWays: Int = 2, rasDepth: Int = 8, instructionSlots: Int = 8) {
  require(historyBits >= 2 && historyBits <= 12)
  require(isPow2(phtBanks) && phtBanks <= (1 << historyBits))
  require(isPow2(btbSets) && btbSets >= 2)
  require(isPow2(btbWays) && btbWays >= 2)
  require(isPow2(rasDepth) && rasDepth >= 2)
  require(isPow2(instructionSlots) && instructionSlots >= 4)
}

class PipelinePhtUpdate(h: Int) extends Bundle { val index = UInt(h.W); val taken = Bool() }
class PipelineBtbUpdate extends Bundle {
  val pc = UInt(64.W) // final parcel address, not necessarily instruction start
  val target = UInt(64.W)
  val kind = UInt(2.W) // 0 none, 1 conditional, 2 JAL, 3 JALR
  val invalidate = Bool()
}
class PipelineBtbHit extends Bundle {
  val hit = Bool(); val target = UInt(64.W); val kind = UInt(2.W)
}
class PipelinePrediction(h: Int) extends Bundle {
  val history = UInt(h.W)
  val index = Vec(3, UInt(h.W))
  val available = Vec(3, Bool())
  val taken = Vec(3, Bool())
  val btb = Vec(2, new PipelineBtbHit)
}

/** Three logical queries, single read per bank, no native FPGA 2R primitive. */
class BreezeBankedPht(p: BreezeFrontendPipelineParams) extends Module {
  private val bankBits = log2Ceil(p.phtBanks)
  private val rows = (1 << p.historyBits) / p.phtBanks
  private def bank(index: UInt): UInt = if (bankBits == 0) 0.U else index(bankBits - 1, 0)
  private def row(index: UInt): UInt = index >> bankBits
  val io = IO(new Bundle {
    val query = Flipped(Valid(Vec(3, UInt(p.historyBits.W))))
    val available = Output(Vec(3, Bool()))
    val taken = Output(Vec(3, Bool()))
    val train = Flipped(Decoupled(new PipelinePhtUpdate(p.historyBits)))
  })
  val ram = Seq.fill(p.phtBanks)(SyncReadMem(rows, UInt(2.W)))
  val initialized = RegInit(VecInit(Seq.fill(1 << p.historyBits)(false.B)))
  val updates = Module(new Queue(new PipelinePhtUpdate(p.historyBits), 4))
  updates.io.enq <> io.train
  val trainActive = RegInit(false.B)
  val training = Reg(new PipelinePhtUpdate(p.historyBits))
  val trainWasInitialized = Reg(Bool())
  updates.io.deq.ready := !trainActive
  val trainRead = updates.io.deq.fire
  trainActive := trainRead
  when(trainRead) {
    training := updates.io.deq.bits
  }
  val readData = Wire(Vec(p.phtBanks, UInt(2.W)))
  val readInitialized = Wire(Vec(p.phtBanks, Bool()))
  val granted = Wire(Vec(3, Bool()))
  granted := VecInit(Seq.fill(3)(false.B))
  val selectedIndex = Wire(Vec(p.phtBanks, UInt(p.historyBits.W)))
  for (b <- 0 until p.phtBanks) {
    val requests = VecInit(io.query.bits.map(i => bank(i) === b.U))
    val any = io.query.valid && requests.asUInt.orR
    val prediction = Mux1H(PriorityEncoderOH(requests.asUInt), io.query.bits)
    val trainBank = trainRead && bank(updates.io.deq.bits.index) === b.U
    selectedIndex(b) := Mux(trainBank, updates.io.deq.bits.index, prediction)
    readData(b) := ram(b).read(row(selectedIndex(b)), trainBank || any)
    // The initialized bit belongs to the same selected row as the counter.
    // Read just this bank's rows once, sharing it between training and all
    // granted identical queries. Denied queries are masked by available.
    val bankInitialized = VecInit((0 until rows).map(r => initialized((r << bankBits) | b)))
    readInitialized(b) := (if (rows == 1) bankInitialized(0)
                          else bankInitialized(row(selectedIndex(b))))
    for (q <- 0 until 3) {
      when(io.query.valid && bank(io.query.bits(q)) === b.U) {
        granted(q) := !trainBank && io.query.bits(q) === prediction
      }
    }
  }
  when(trainRead) { trainWasInitialized := readInitialized(bank(updates.io.deq.bits.index)) }
  val oldCounter = Mux(trainWasInitialized, readData(bank(training.index)), 1.U(2.W))
  val updated = Mux(training.taken,
    Mux(oldCounter === 3.U, 3.U, oldCounter + 1.U),
    Mux(oldCounter === 0.U, 0.U, oldCounter - 1.U))
  for (b <- 0 until p.phtBanks) {
    when(trainActive && bank(training.index) === b.U) { ram(b).write(row(training.index), updated) }
  }
  when(trainActive) { initialized(training.index) := true.B }
  for (q <- 0 until 3) {
    val available = RegNext(granted(q), false.B)
    val index = RegEnable(io.query.bits(q), io.query.valid)
    val init = RegEnable(readInitialized(bank(io.query.bits(q))), io.query.valid)
    // SyncReadMem same-address read/write is unspecified. Forward the write
    // sampled at the read edge instead, including the first-ever training.
    val bypass = RegNext(io.query.valid && trainActive && training.index === io.query.bits(q), false.B)
    val bypassCounter = RegEnable(updated, io.query.valid && trainActive)
    val counter = Mux(bypass, bypassCounter, Mux(init, readData(bank(index)), 1.U(2.W)))
    io.available(q) := available
    io.taken(q) := available && counter(1)
  }
}

/** Two halfword banks, synchronous associative reads; updates share a bank's
  * single read port. A denied prediction is a miss, repaired by F2/resolve.
  */
class BreezeBankedBtb(p: BreezeFrontendPipelineParams) extends Module {
  private val setBits = log2Ceil(p.btbSets)
  private val tagBits = 62 - setBits
  class Entry extends Bundle { val tag = UInt(tagBits.W); val target = UInt(64.W); val kind = UInt(2.W) }
  def set(pc: UInt): UInt = pc(setBits + 1, 2)
  def tag(pc: UInt): UInt = pc(63, setBits + 2)
  val io = IO(new Bundle {
    val query = Flipped(Valid(UInt(64.W)))
    val result = Output(Vec(2, new PipelineBtbHit))
    val update = Flipped(Decoupled(new PipelineBtbUpdate))
    val repair = Flipped(Valid(new PipelineBtbUpdate))
  })
  val ram = Seq.fill(2, p.btbWays)(SyncReadMem(p.btbSets, new Entry))
  val valid = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(p.btbSets)(0.U(p.btbWays.W))))))
  val rr = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(p.btbSets)(0.U(log2Ceil(p.btbWays).W))))))
  val writes = Module(new Queue(new PipelineBtbUpdate, 4))
  writes.io.enq.valid := io.update.valid || io.repair.valid
  writes.io.enq.bits := Mux(io.update.valid, io.update.bits, io.repair.bits)
  io.update.ready := writes.io.enq.ready
  val active = RegInit(false.B)
  val update = Reg(new PipelineBtbUpdate)
  val updateValid = Reg(UInt(p.btbWays.W))
  writes.io.deq.ready := !active
  val start = writes.io.deq.fire
  active := start
  when(start) {
    update := writes.io.deq.bits
    updateValid := valid(writes.io.deq.bits.pc(1))(set(writes.io.deq.bits.pc))
  }
  val data = Wire(Vec(2, Vec(p.btbWays, new Entry)))
  val queryPc = RegEnable(io.query.bits, io.query.valid)
  val readValid = Reg(Vec(2, UInt(p.btbWays.W)))
  val queryEnabled = Wire(Vec(2, Bool()))
  val queried = RegNext(queryEnabled, VecInit(Seq.fill(2)(false.B)))
  for (b <- 0 until 2) {
    val pc = Mux(io.query.bits(1) === b.U, io.query.bits, io.query.bits + 2.U)
    val updating = start && writes.io.deq.bits.pc(1) === b.U
    val writing = active && update.pc(1) === b.U
    queryEnabled(b) := io.query.valid && !updating && !writing
    val address = Mux(updating, set(writes.io.deq.bits.pc), set(pc))
    when(queryEnabled(b)) { readValid(b) := valid(b)(set(pc)) }
    for (w <- 0 until p.btbWays) { data(b)(w) := ram(b)(w).read(address, updating || queryEnabled(b)) }
  }
  val updateData = data(update.pc(1))
  val matches = VecInit((0 until p.btbWays).map(w => updateValid(w) && updateData(w).tag === tag(update.pc)))
  val empty = ~updateValid
  val victim = Mux(matches.asUInt.orR, PriorityEncoder(matches),
    Mux(empty.orR, PriorityEncoder(empty), rr(update.pc(1))(set(update.pc))))
  val value = Wire(new Entry)
  value.tag := tag(update.pc); value.target := update.target; value.kind := update.kind
  when(active) {
    for (b <- 0 until 2; s <- 0 until p.btbSets) {
      when(update.pc(1) === b.U && set(update.pc) === s.U) {
        when(update.invalidate) { valid(b)(s) := valid(b)(s) & ~matches.asUInt }
          .otherwise {
            valid(b)(s) := valid(b)(s) | UIntToOH(victim, p.btbWays)
            when(!matches.asUInt.orR) { rr(b)(s) := victim + 1.U }
          }
      }
    }
    for (b <- 0 until 2; w <- 0 until p.btbWays) {
      when(!update.invalidate && update.pc(1) === b.U && victim === w.U) {
        ram(b)(w).write(set(update.pc), value)
      }
    }
  }
  for (slot <- 0 until 2) {
    val pc = queryPc + (slot * 2).U
    val b = pc(1)
    val hits = VecInit((0 until p.btbWays).map(w => readValid(b)(w) && data(b)(w).tag === tag(pc)))
    val winner = PriorityEncoderOH(hits.asUInt)
    io.result(slot).hit := queried(b) && hits.asUInt.orR
    io.result(slot).target := Mux1H(winner, data(b).map(_.target))
    io.result(slot).kind := Mux(io.result(slot).hit, Mux1H(winner, data(b).map(_.kind)), 0.U)
    when(queried(b)) { assert(PopCount(hits) <= 1.U, "BTB duplicate event PC") }
  }
}

class BreezePipelinePredictor(p: BreezeFrontendPipelineParams) extends Module {
  val io = IO(new Bundle {
    val query = Flipped(Valid(new Bundle { val pc = UInt(64.W); val history = UInt(p.historyBits.W) }))
    val result = Output(new PipelinePrediction(p.historyBits))
    val phtUpdate = Flipped(Decoupled(new PipelinePhtUpdate(p.historyBits)))
    val btbUpdate = Flipped(Decoupled(new PipelineBtbUpdate))
    val btbRepair = Flipped(Valid(new PipelineBtbUpdate))
  })
  val pht = Module(new BreezeBankedPht(p))
  val btb = Module(new BreezeBankedBtb(p))
  val shifted = Cat(io.query.bits.history(p.historyBits - 2, 0), 0.U(1.W))
  val pc0 = io.query.bits.pc(p.historyBits, 1)
  val pc1 = (io.query.bits.pc + 2.U)(p.historyBits, 1)
  val indices = VecInit(Seq(pc0 ^ io.query.bits.history, pc1 ^ io.query.bits.history, pc1 ^ shifted))
  pht.io.query.valid := io.query.valid; pht.io.query.bits := indices
  btb.io.query.valid := io.query.valid; btb.io.query.bits := io.query.bits.pc
  io.result.history := RegEnable(io.query.bits.history, io.query.valid)
  io.result.index := RegEnable(indices, io.query.valid)
  io.result.available := pht.io.available; io.result.taken := pht.io.taken
  io.result.btb := btb.io.result
  pht.io.train <> io.phtUpdate; btb.io.update <> io.btbUpdate; btb.io.repair <> io.btbRepair
}

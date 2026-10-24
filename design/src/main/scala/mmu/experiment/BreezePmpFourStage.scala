package flow.mmu.experiment

import chisel3._
import chisel3.util._
import flow.core.CSRMAP
import flow.interface.{BreezeMmuAccess, BreezePmpRange}
import flow.mmu.BreezePmpDecode

class PmpCsrCommit extends Bundle {
  val address = UInt(12.W)
  val data = UInt(64.W)
}

/** Standalone CSR-side predecode. Input is an already-authorized CSR commit.
  * Raw state, intervals and boundary predecessors update on the same edge.
  * This module does not implement CSR instruction execution or Smepmp.
  */
class BreezePmpCsrPredecode(val entries: Int = 8) extends Module {
  require(entries >= 1 && entries <= 16)
  val io = IO(new Bundle {
    val commit = Flipped(Valid(new PmpCsrCommit))
    val cfg = Output(Vec(entries, UInt(8.W)))
    val address = Output(Vec(entries, UInt(54.W)))
    val ranges = Output(Vec(entries, new BreezePmpRange))
    val changed = Output(Bool())
    val epoch = Output(UInt(16.W))
  })
  val cfg = RegInit(VecInit(Seq.fill(entries)(0.U(8.W))))
  val address = RegInit(VecInit(Seq.fill(entries)(0.U(54.W))))
  val ranges = RegInit(VecInit(Seq.fill(entries)(
    BreezePmpDecode.range(0.U(8.W), 0.U(54.W), 0.U(54.W)))))
  val nextCfg = WireDefault(cfg)
  val nextAddress = WireDefault(address)
  val isCfg = io.commit.bits.address === CSRMAP.pmpcfg0.U ||
    io.commit.bits.address === CSRMAP.pmpcfg2.U
  val bank = Mux(io.commit.bits.address === CSRMAP.pmpcfg0.U, 0.U, 8.U)
  val isAddr = io.commit.bits.address >= CSRMAP.pmpaddr0.U &&
    io.commit.bits.address < (CSRMAP.pmpaddr0 + entries).U
  io.changed := io.commit.valid && (isCfg || isAddr)
  for (i <- 0 until entries) {
    val requested = io.commit.bits.data((i % 8) * 8 + 7, (i % 8) * 8)
    val legal = Cat(requested(7), 0.U(2.W), requested(4, 3), requested(2),
      requested(1) && requested(0), requested(0))
    when(isCfg && bank === (i / 8 * 8).U && !cfg(i)(7)) {
      nextCfg(i) := legal
    }
    val nextTorLocked = if (i + 1 == entries) false.B else
      cfg(i + 1)(7) && cfg(i + 1)(4, 3) === 1.U
    when(io.commit.bits.address === (CSRMAP.pmpaddr0 + i).U &&
      !cfg(i)(7) && !nextTorLocked) {
      nextAddress(i) := io.commit.bits.data(53, 0)
    }
  }
  val epoch = RegInit(0.U(16.W))
  when(io.changed) {
    cfg := nextCfg
    address := nextAddress
    for (i <- 0 until entries) {
      ranges(i) := BreezePmpDecode.range(nextCfg(i), nextAddress(i),
        if (i == 0) 0.U(54.W) else nextAddress(i - 1))
    }
    epoch := epoch + 1.U
  }
  io.cfg := cfg
  io.address := address
  io.ranges := ranges
  io.epoch := epoch
}

class PmpPipelineRequest extends Bundle {
  val id = UInt(16.W)
  val address = UInt(64.W)
  val sizeLog2 = UInt(3.W) // 1..128 bytes, including unaligned accesses
  val access = BreezeMmuAccess()
  val privilege = UInt(2.W) // effective privilege supplied by caller
}

class PmpPipelineResponse(val entries: Int) extends Bundle {
  val id = UInt(16.W)
  val epoch = UInt(16.W)
  val allowed = Bool()
  val matched = Bool()
  val winnerOH = UInt(entries.W)
}

private class PmpLowMeta(entries: Int) extends Bundle {
  val id = UInt(16.W)
  val epoch = UInt(16.W)
  val machine = Bool()
  val carry = Bool()
  // start<lower, start<upper, wrapped-last<lower, wrapped-last<upper
  val low = Vec(entries, Vec(4, Bool()))
  val boundaryNonzero = Vec(entries, Vec(2, Bool()))
  val nonempty = Vec(entries, Bool())
  val permission = Vec(entries, Bool())
}

private class PmpChunkCompare extends Bundle {
  val lt = Bool()
  val eq = Bool()
}

/** Four registered computation stages, II=1, no iTLB or cache dependencies.
  * P0: low-byte/end comparisons and permission snapshot.
  * P1: independent <=8-bit high-address comparisons, including predecessors.
  * P2: balanced merge, overlap and full-byte coverage.
  * P3: lowest-numbered winner and permission response.
  *
  * A request fires on edge N; its response is valid after edge N+3.
  * Response has no ready: the consumer must accept every valid result.
  * cancel/configChanged discard all in-flight work and inhibit acceptance.
  * Configuration must remain stable unless configChanged is asserted.
  */
class BreezePmpFourStage(val entries: Int = 8) extends Module {
  require(entries >= 1)
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new PmpPipelineRequest))
    val response = Valid(new PmpPipelineResponse(entries))
    val cfg = Input(Vec(entries, UInt(8.W)))
    val ranges = Input(Vec(entries, new BreezePmpRange))
    val epoch = Input(UInt(16.W))
    val configChanged = Input(Bool())
    val cancel = Input(Bool())
  })
  val clear = io.cancel || io.configChanged
  io.request.ready := !clear && !reset.asBool
  val valid = RegInit(VecInit(Seq.fill(4)(false.B)))
  valid(0) := io.request.fire
  for (i <- 1 until 4) valid(i) := valid(i - 1)
  when(clear) { valid.foreach(_ := false.B) }

  val low = io.request.bits.address(6, 0)
  val end = BreezePmpDecode.accessEnd(low, io.request.bits.sizeLog2)
  val lowMeta = Wire(new PmpLowMeta(entries))
  lowMeta.id := io.request.bits.id
  lowMeta.epoch := io.epoch
  lowMeta.machine := io.request.bits.privilege === 3.U
  lowMeta.carry := end.carry
  for (i <- 0 until entries) {
    val r = io.ranges(i)
    lowMeta.low(i)(0) := low < r.lower(6, 0)
    lowMeta.low(i)(1) := low < r.upper(6, 0)
    lowMeta.low(i)(2) := end.low < r.lower(6, 0)
    lowMeta.low(i)(3) := end.low < r.upper(6, 0)
    lowMeta.boundaryNonzero(i)(0) := r.lower(64, 7).orR
    lowMeta.boundaryNonzero(i)(1) := r.upper(64, 7).orR
    lowMeta.nonempty(i) := r.nonempty
    val rwx = MuxLookup(io.request.bits.access.asUInt, false.B)(Seq(
      BreezeMmuAccess.Fetch.asUInt -> io.cfg(i)(2),
      BreezeMmuAccess.Load.asUInt -> io.cfg(i)(0),
      BreezeMmuAccess.Store.asUInt -> io.cfg(i)(1)))
    lowMeta.permission(i) := (lowMeta.machine && !io.cfg(i)(7)) || rwx
  }
  // Payloads are not reset or valid-gated: validity owns all observable effects.
  val p0 = RegNext(lowMeta)
  val p0High = RegNext(Cat(0.U(1.W), io.request.bits.address(63, 7)))

  val chunkCount = 8 // 58-bit high part split into 7x8 + 2 bits
  val chunkValues = Wire(Vec(entries, Vec(4, Vec(chunkCount, new PmpChunkCompare))))
  for (i <- 0 until entries) {
    val r = io.ranges(i)
    val bounds = Seq(r.lower(64, 7), r.upper(64, 7),
      r.lowerBlockPrevious, r.upperBlockPrevious)
    for (b <- 0 until 4; c <- 0 until chunkCount) {
      val hi = math.min(57, c * 8 + 7)
      val a = p0High(hi, c * 8)
      val z = bounds(b)(hi, c * 8)
      chunkValues(i)(b)(c).lt := a < z
      chunkValues(i)(b)(c).eq := a === z
    }
  }
  val p1Chunks = RegNext(chunkValues)
  val p1 = RegNext(p0)

  def merge(parts: Seq[(Bool, Bool)]): (Bool, Bool) = {
    if (parts.size == 1) parts.head else {
      val (hl, he) = merge(parts.take(parts.size / 2))
      val (ll, le) = merge(parts.drop(parts.size / 2))
      (hl || (he && ll), he && le)
    }
  }
  val overlaps = Wire(Vec(entries, Bool()))
  val permits = Wire(Vec(entries, Bool()))
  for (i <- 0 until entries) {
    val full = (0 until 4).map { b =>
      merge((0 until chunkCount).reverse.map(c =>
        (p1Chunks(i)(b)(c).lt, p1Chunks(i)(b)(c).eq)))
    }
    def startBelow(b: Int): Bool = full(b)._1 || (full(b)._2 && p1.low(i)(b))
    def lastBelow(b: Int): Bool = {
      val same = full(b)._1 || (full(b)._2 && p1.low(i)(b + 2))
      val next = p1.boundaryNonzero(i)(b) &&
        (full(b + 2)._1 || (full(b + 2)._2 && p1.low(i)(b + 2)))
      Mux(p1.carry, next, same)
    }
    overlaps(i) := p1.nonempty(i) && startBelow(1) && !lastBelow(0)
    permits(i) := !startBelow(0) && lastBelow(1) && p1.permission(i)
  }
  val p2Overlaps = RegNext(overlaps)
  val p2Permits = RegNext(permits)
  val p2Id = RegNext(p1.id)
  val p2Epoch = RegNext(p1.epoch)
  val p2Machine = RegNext(p1.machine)
  val winner = PriorityEncoderOH(p2Overlaps.asUInt)
  val p3 = Wire(new PmpPipelineResponse(entries))
  p3.id := p2Id
  p3.epoch := p2Epoch
  p3.winnerOH := winner
  p3.matched := p2Overlaps.asUInt.orR
  p3.allowed := (winner & p2Permits.asUInt).orR || (!p3.matched && p2Machine)
  io.response.bits := RegNext(p3)
  io.response.valid := valid(3) && !clear
}

/** Composition under test: real raw CSR state + registered predecode + pipeline. */
class BreezePmpFourStageExperiment(val entries: Int = 8) extends Module {
  val io = IO(new Bundle {
    val commit = Flipped(Valid(new PmpCsrCommit))
    val request = Flipped(Decoupled(new PmpPipelineRequest))
    val cancel = Input(Bool())
    val response = Valid(new PmpPipelineResponse(entries))
  })
  val csr = Module(new BreezePmpCsrPredecode(entries))
  val checker = Module(new BreezePmpFourStage(entries))
  csr.io.commit := io.commit
  checker.io.cfg := csr.io.cfg
  checker.io.ranges := csr.io.ranges
  checker.io.epoch := csr.io.epoch
  checker.io.configChanged := csr.io.changed
  checker.io.cancel := io.cancel
  checker.io.request <> io.request
  io.response := checker.io.response
}

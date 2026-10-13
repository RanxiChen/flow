package flow.rvv.zve

import chisel3._
import chisel3.util._

/** Line-granular VLSU for pre-translated unit-stride accesses (normal, whole
  * register, mask). The issuer already translated and PMP/PMA-checked every
  * byte before commit, so no request here can trap; bus errors are fatal.
  *
  * Loads: one coherent L2 Read per touched 32 B line, up to `streamCredits`
  * outstanding, returned in any order. Rows are at least one line wide, so a
  * returned line covers at most two VRF rows; each becomes a byte-enabled
  * write, so tail/masked bytes stay undisturbed and no row assembly buffer is
  * needed. The SoC instance has row == line (32 B); 64 B rows also work.
  *
  * Stores: per line, read the (at most two) source rows, rotate them into the
  * line and send one MaskWrite with the active-byte mask. The L2 serializes
  * same-set requests from one port in issue order, so program order between
  * lines of successive stream instructions is kept. One instruction is
  * active at a time; its lines are pipelined (zve32f-single §5). */
class ZveStreamEngine(p: ZveParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ZveCommand(p)))
    val vrf = new ZveVrfPort(p); val mask = Input(UInt(p.vrf.vlen.W))
    val line = new ZveLineIO(p)
    val complete = Decoupled(new ZveCompletion(p)); val drained = Output(Bool())
    val fatal = Output(Bool())
    val perfIdCount = Output(UInt(log2Ceil(p.streamCredits + 1).W))
    val perfLoadDone = Output(Bool())
  })
  require(isPow2(p.vrf.rowBytes) && isPow2(p.lineBytes) && p.vrf.rowBytes >= p.lineBytes,
    "stream VLSU needs power-of-two rows at least one line wide")
  val R = p.vrf.rowBytes; val L = p.lineBytes; val C = p.streamCredits
  val lineBits = log2Ceil(L); val rowBits = log2Ceil(R); val pageBits = p.pageBits
  val countBits = log2Ceil(p.vrf.maxBytes / L + 2)

  val active = RegInit(false.B); val d = Reg(new ZveCommand(p)); val mask = Reg(UInt(p.vrf.vlen.W))
  io.in.ready := !active
  val x = d.issue.scalar.instruction
  val store = x(6, 0) === 0x27.U
  val maskMemory = x(24, 20) === 11.U
  val unmasked = x(25) || d.whole || maskMemory
  val eew = Mux(d.whole || maskMemory, 0.U, d.destinationWidth)
  val va = d.issue.scalar.rs1
  val total = Mux(d.whole, (x(31, 29) +& 1.U) * p.vrf.regBytes.U,
    Mux(maskMemory, (d.issue.scalar.vl +& 7.U) >> 3, d.issue.scalar.vl << eew))
  val offset = va(lineBits - 1, 0)
  val lines = Reg(UInt(countBits.W))
  val baseRow = (x(11, 7) * p.vrf.rowsPerReg.U)(p.vrf.rowBits - 1, 0)
  when(io.in.fire) {
    active := true.B; d := io.in.bits; mask := io.mask
    val c = io.in.bits; val cva = c.issue.scalar.rs1; val cx = c.issue.scalar.instruction
    val cMask = cx(24, 20) === 11.U
    val cEew = Mux(c.whole || cMask, 0.U, c.destinationWidth)
    val n = Mux(c.whole, (cx(31, 29) +& 1.U) * p.vrf.regBytes.U,
      Mux(cMask, (c.issue.scalar.vl +& 7.U) >> 3, c.issue.scalar.vl << cEew))
    lines := (((cva + n - 1.U) >> lineBits) - (cva >> lineBits) + 1.U)(countBits - 1, 0)
    assert(c.issue.pre.valid && n =/= 0.U && c.issue.scalar.vstart === 0.U, "stream VLSU needs a non-empty pre-translated access")
  }

  // Line k: start offset s = L*k - offset relative to the first access byte.
  def lineStart(k: UInt): SInt = ((k << lineBits).zext - offset.zext)
  def linePa(k: UInt): UInt = {
    val lineVa = (va >> lineBits) + k
    val second = (lineVa >> (pageBits - lineBits)) =/= (va >> pageBits)
    Cat(Mux(second, d.issue.pre.page1, d.issue.pre.page0), lineVa(pageBits - lineBits - 1, 0))
  }
  // Byte-expanded v0 for relative row r: one bit per row byte.
  def rowActive(r: UInt): UInt = {
    val e0 = (mask >> (r << rowBits))(R - 1, 0)
    val e1 = Cat((0 until R / 2).reverse.map(j => Fill(2, (mask >> (r << (rowBits - 1)))(j))))
    val e2 = Cat((0 until R / 4).reverse.map(j => Fill(4, (mask >> (r << (rowBits - 2)))(j))))
    Mux(unmasked, Fill(R, 1.U(1.W)), MuxLookup(eew, e0)(Seq(1.U -> e1, 2.U -> e2)))
  }
  // Rows touched by a line: [lo, hi] relative to the destination group.
  def rowRange(k: UInt): (UInt, UInt) = {
    val s = lineStart(k)
    val first = Mux(s < 0.S, 0.U, s.asUInt)
    val last = Mux((s + (L - 1).S).asUInt >= total, total - 1.U, (s + (L - 1).S).asUInt)
    (first >> rowBits, last >> rowBits)
  }

  // ---- ID ownership: free list; an ID lives from request to response.
  val busy = RegInit(0.U(C.W)); val owner = Reg(Vec(C, UInt(countBits.W)))
  io.perfIdCount := PopCount(busy)
  val freeId = PriorityEncoder(~busy); val hasFree = !busy.andR
  // A load request can wait in the L2 input pipeline while an older response
  // releases another ID. Pin its ID until the request handshake.
  val loadHeld = RegInit(false.B); val loadHeldId = Reg(UInt(log2Ceil(C).W))
  val loadId = Mux(loadHeld, loadHeldId, freeId)
  val issued = RegInit(0.U(countBits.W)); val finished = RegInit(0.U(countBits.W))
  val fatal = RegInit(false.B); io.fatal := fatal
  when(io.in.fire) { issued := 0.U; finished := 0.U }
  val allocate = WireDefault(false.B)
  val releaseSkip = WireDefault(0.U(C.W)); val releaseRsp = WireDefault(0.U(C.W))
  val finishSkip = WireDefault(false.B); val finishAck = WireDefault(false.B); val finishLoad = WireDefault(false.B)
  val allocatedId = Mux(store, freeId, loadId)
  busy := (busy | Mux(allocate, UIntToOH(allocatedId, C), 0.U)) & ~(releaseSkip | releaseRsp)
  when(!io.in.fire) { finished := finished + finishSkip.asUInt + finishAck.asUInt + finishLoad.asUInt }

  // ---- Loads: issue Reads in line order.
  val loadIssue = active && !store && issued < lines && hasFree
  // ---- Stores: source rows are read first; the ID is reserved at that point.
  val storeRead = active && store && issued < lines && hasFree
  val (readLo, readHi) = rowRange(issued)
  io.vrf.read.valid := storeRead
  io.vrf.read.bits.tag := Cat(freeId, issued).pad(16)
  io.vrf.read.bits.age := d.age
  io.vrf.read.bits.rows(0) := baseRow + readLo
  io.vrf.read.bits.rows(1) := baseRow + readLo + 1.U
  io.vrf.read.bits.rows(2) := 0.U
  io.vrf.read.bits.enables := Cat(false.B, readHi =/= readLo, true.B)

  // Store build stage: rotate two rows into the line, then MaskWrite.
  val built = RegInit(false.B); val builtReq = Reg(new ZveLineRequest(p))
  val storeLine = io.vrf.returned.bits.tag(countBits - 1, 0)
  val storeId = io.vrf.returned.bits.tag(countBits + log2Ceil(C) - 1, countBits)
  io.vrf.returned.ready := !built || io.line.request.fire
  when(io.vrf.returned.fire) {
    val s = lineStart(storeLine)
    val (lo, _) = rowRange(storeLine)
    val u = (s - (lo << rowBits).zext)(rowBits, 0) // (s - R*lo) mod 2R
    val both = Cat(io.vrf.returned.bits.data(1), io.vrf.returned.bits.data(0))
    val bothActive = Cat(rowActive(lo + 1.U), rowActive(lo))
    val rot = ((Cat(both, both) >> (u << 3))(8 * L - 1, 0))
    val rotActive = ((Cat(bothActive, bothActive) >> u)(L - 1, 0))
    val inRange = VecInit((0 until L).map { j =>
      val i = s + j.S
      i >= 0.S && i.asUInt < total
    }).asUInt
    built := true.B
    builtReq.id := storeId; builtReq.write := true.B; builtReq.line := linePa(storeLine)
    builtReq.data := rot; builtReq.mask := rotActive & inRange
  }
  val skipEmpty = built && builtReq.mask === 0.U

  io.line.request.valid := loadIssue || (built && !skipEmpty)
  io.line.request.bits := Mux(built, builtReq, {
    val r = Wire(new ZveLineRequest(p))
    r.id := loadId; r.write := false.B; r.line := linePa(issued)
    r.mask := 0.U; r.data := 0.U; r
  })
  val loadFire = io.line.request.fire && !built
  when(loadIssue && !io.line.request.ready && !loadHeld) {
    loadHeld := true.B; loadHeldId := freeId
  }
  when(loadFire) { loadHeld := false.B; owner(loadId) := issued }
  when((loadFire) || io.vrf.read.fire) { allocate := true.B; issued := issued + 1.U }
  when(io.vrf.read.fire) { owner(freeId) := issued }
  // A new VRF return may replace the request accepted by L2 in this cycle.
  // Keep that new request resident in the build stage.
  when((io.line.request.fire && built || skipEmpty) && !io.vrf.returned.fire) { built := false.B }
  when(skipEmpty) { releaseSkip := UIntToOH(builtReq.id, C); finishSkip := true.B }

  // ---- Responses. Store: WriteAck finishes the line. Load: two-row writes.
  val pending = RegInit(false.B); val pendLine = Reg(UInt(countBits.W)); val pendData = Reg(UInt((8 * L).W))
  val phase = RegInit(false.B)
  val rsp = io.line.response
  rsp.ready := store || !pending
  when(rsp.fire) {
    assert(busy(rsp.bits.id), "stream response for an idle ID")
    when(rsp.bits.error) { fatal := true.B }
    releaseRsp := UIntToOH(rsp.bits.id, C)
    when(store) { finishAck := true.B }
      .otherwise { pending := true.B; pendLine := owner(rsp.bits.id); pendData := rsp.bits.data; phase := false.B }
  }
  val (wLo, wHi) = rowRange(pendLine)
  val wRow = wLo + phase.asUInt
  val ps = lineStart(pendLine)
  // Row byte b of any row holds line byte (b + offset) mod L (R*r - L*k is
  // a multiple of L); enables select the bytes this line really covers.
  val rotLine = (Cat(pendData, pendData) >> (offset << 3))(8 * L - 1, 0)
  val rowData = Fill(R / L, rotLine)
  val rowAct = rowActive(wRow)
  val enables = VecInit((0 until R).map { b =>
    val i = (wRow << rowBits).zext + b.S
    i >= ps && i < ps + L.S && i.asUInt < total && rowAct(b)
  }).asUInt
  io.vrf.write.valid := pending && enables.orR
  io.vrf.write.bits.tag := pendLine
  io.vrf.write.bits.age := d.age
  io.vrf.write.bits.row := baseRow + wRow
  io.vrf.write.bits.data := rowData
  io.vrf.write.bits.enables := enables
  val rowDone = pending && (io.vrf.write.fire || !enables.orR)
  val lastRow = phase || wHi === wLo
  when(rowDone) {
    when(lastRow) { pending := false.B; finishLoad := true.B }.otherwise { phase := true.B }
  }
  val writes = RegInit(0.U(8.W)); val acks = RegInit(0.U(8.W))
  when(io.in.fire) { writes := 0.U; acks := 0.U }
  when(io.vrf.write.fire) { writes := writes + 1.U }
  io.vrf.written.ready := true.B
  when(io.vrf.written.fire) { acks := acks + 1.U }

  io.complete.valid := active && finished === lines && issued === lines && acks === writes &&
    !pending && !built && busy === 0.U
  io.perfLoadDone := io.complete.fire && !store
  io.complete.bits := 0.U.asTypeOf(new ZveCompletion(p))
  io.complete.bits.slot := d.slot; io.complete.bits.age := d.age
  when(io.complete.fire) { active := false.B }
  io.drained := !active
}

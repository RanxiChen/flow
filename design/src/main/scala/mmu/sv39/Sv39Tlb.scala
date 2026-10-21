package flow.mmu.sv39

import chisel3._
import chisel3.util._

class Sv39Tlb(p: Sv39TlbParams, instruction: Boolean,
              withPmpCandidate: Boolean = false, withFetchCandidates: Boolean = false,
              pipelinedFetchKill: Boolean = false) extends Module {
  require(!withFetchCandidates || instruction)
  require(!pipelinedFetchKill || instruction)
  val io = IO(new Bundle {
    val port = new TlbPortIO(withPmpCandidate, if (withFetchCandidates) p.ways + p.superpages + 1 else 0)
    val csr = Input(new MmuCsrIO); val sfence = Input(new SfenceIO)
    val miss = Output(new TlbMiss); val grant = Input(Bool())
    val done = Flipped(Valid(new PtwDone)); val idle = Output(Bool())
    val observation = Output(new flow.observe.BreezeObserveEvents)
    val walkKilled = Output(Bool())
  })
  private val setBits = log2Ceil(p.sets)
  def index(vpn: UInt): UInt = if (p.sets == 1) 0.U else vpn(setBits - 1, 0)
  def tag(vpn: UInt): UInt = vpn >> setBits
  def superMatch(e: SuperEntry, vpn: UInt): Bool =
    Mux(e.level === 2.U, e.vpn(26, 18) === vpn(26, 18), e.vpn(26, 9) === vpn(26, 9))
  // Independent unmasked bank/way ports keep late refill authorization local.
  private val banks = math.min(4, p.sets)
  private val rows = p.sets / banks
  private val rowBits = log2Ceil(rows)
  private def bankOf(s: UInt): UInt = if (banks == 1) 0.U(1.W) else s(setBits - 1, rowBits)
  private def rowOf(s: UInt): UInt = if (rows == 1) 0.U(1.W) else s(rowBits - 1, 0)
  val mem = Seq.tabulate(banks, p.ways) { (_, _) => SyncReadMem(rows, new BaseEntry(27 - setBits)) }
  val baseValid = RegInit(VecInit(Seq.fill(p.sets)(VecInit(Seq.fill(p.ways)(false.B)))))
  val basePlru = RegInit(VecInit(Seq.fill(p.sets)(0.U((p.ways - 1).W))))
  val sp = RegInit(VecInit(Seq.fill(p.superpages)(0.U.asTypeOf(new SuperEntry))))
  val spPlru = RegInit(0.U((p.superpages - 1).W))
  // A late F2 kill qualifies just one pending-valid bit, rather than every
  // PLRU register CE. Forward this touch to replacement selection so the
  // logical state is identical to the original same-edge hit update.
  private val deferHitTouch = instruction && pipelinedFetchKill
  val touchPending = RegInit(false.B)
  val touchBase = Reg(Bool())
  val touchSet = Reg(UInt(setBits.max(1).W))
  val touchWay = Reg(UInt(log2Ceil(p.ways.max(p.superpages)).max(1).W))
  val visibleBasePlru = Wire(chiselTypeOf(basePlru))
  val visibleSpPlru = Wire(chiselTypeOf(spPlru))
  visibleBasePlru := basePlru
  visibleSpPlru := spPlru
  if (deferHitTouch) {
    for (s <- 0 until p.sets) {
      when(touchPending && touchBase && touchSet === s.U) {
        visibleBasePlru(s) := TreePlru.touch(basePlru(s), touchWay, p.ways)
        basePlru(s) := visibleBasePlru(s)
      }
    }
    when(touchPending && !touchBase) {
      visibleSpPlru := TreePlru.touch(spPlru, touchWay, p.superpages)
      spPlru := visibleSpPlru
    }
  }
  val s1Valid = RegInit(false.B)
  val s1Vaddr = Reg(UInt(64.W)); val s1Cmd = Reg(MmuCmd())
  val s1FromFault = Reg(Bool()); val s1FaultIsAccess = Reg(Bool())
  val missValid = RegInit(false.B); val missGranted = RegInit(false.B); val missKilled = RegInit(false.B)
  val missVpn = Reg(UInt(27.W)); val missAsid = Reg(UInt(16.W)); val missRootPpn = Reg(UInt(44.W))
  val pendingFault = RegInit(false.B); val pendingFaultIsAccess = Reg(Bool())
  val sfS1Valid = RegInit(false.B)
  val sfVaddr = Reg(UInt(64.W)); val sfRs2Nz = Reg(Bool()); val sfAsid = Reg(UInt(16.W))
  // A pipelined fetch may sample an array query on the cancellation edge;
  // kill clears its valid ownership. Keep kill out of the array enable path.
  // Existing clients retain the original no-fire-on-kill contract.
  // SFENCE is an action on the serial WB boundary, which cancels younger
  // CPU slots. Its raw commit must not become an EX payload enable either.
  io.port.canAccept := !missValid && !sfS1Valid
  io.port.req.ready := io.port.canAccept && !io.sfence.valid &&
    (if (pipelinedFetchKill) true.B else !io.port.kill)
  io.idle := !missValid && !pendingFault && !sfS1Valid && !s1Valid
  io.miss.valid := missValid; io.miss.granted := missGranted
  io.miss.vpn := missVpn; io.miss.asid := missAsid; io.miss.rootPpn := missRootPpn
  val sfRead = io.sfence.valid && io.sfence.rs1Nz
  val readEnable = io.port.req.fire || sfRead
  val readVpn = Mux(sfRead, io.sfence.vaddr(38, 12), io.port.req.bits.vaddr(38, 12))
  val readSet = index(readVpn)
  val readBank = RegEnable(bankOf(readSet), readEnable)
  val bankData = VecInit((0 until banks).map { b =>
    VecInit((0 until p.ways).map { w =>
      mem(b)(w).read(rowOf(readSet), readEnable && bankOf(readSet) === b.U)
    })
  })
  val data = if (banks == 1) bankData(0) else bankData(readBank)
  val vpn = s1Vaddr(38, 12); val set = index(vpn)
  val baseHit = VecInit((0 until p.ways).map(i => baseValid(set)(i) && data(i).tag === tag(vpn) &&
    (data(i).g || data(i).asid === io.csr.asid)))
  val superHit = VecInit(sp.map(e => e.valid && superMatch(e, vpn) && (e.g || e.asid === io.csr.asid)))
  val anyHit = baseHit.asUInt.orR || superHit.asUInt.orR
  def page(e: PageEntry): PageEntry = {
    val result = Wire(new PageEntry); result.assignPage(e); result
  }
  val hits = baseHit.toSeq ++ superHit.toSeq
  val entry = Mux1H(hits, data.toSeq.map(page) ++ sp.toSeq.map(page))
  val effPriv = Mux(s1Cmd =/= MmuCmd.Fetch && io.csr.mprv, io.csr.mpp, io.csr.priv)
  val translate = io.csr.sv39 && effPriv =/= 3.U
  val canonical = !s1Vaddr(63, 38).orR || s1Vaddr(63, 38).andR
  val permFail = !entry.a || (s1Cmd === MmuCmd.Fetch && !entry.x) ||
    (s1Cmd === MmuCmd.Load && !(entry.r || (io.csr.mxr && entry.x))) ||
    (s1Cmd === MmuCmd.Store && !(entry.w && entry.d)) ||
    (effPriv === 0.U && !entry.u) || (effPriv === 1.U && entry.u && (s1Cmd === MmuCmd.Fetch || !io.csr.sum))
  // Form every PA before hit selection; no selected level -> selected PPN mux.
  val basePa = data.toSeq.map(e => Cat(e.ppn, s1Vaddr(11, 0)).pad(64))
  val superPa = sp.toSeq.map(e => Mux(e.level === 2.U,
    Cat(e.ppn(43, 18), s1Vaddr(29, 0)), Cat(e.ppn(43, 9), s1Vaddr(20, 0))).pad(64))
  val pa = Mux1H(hits, basePa ++ superPa)
  io.port.fetchPaddrs.foreach(_ := VecInit(basePa ++ superPa :+ s1Vaddr))
  io.port.fetchSelect.foreach(_ := Cat(!translate, VecInit(hits.map(_ && translate)).asUInt))
  // PMP can compare this candidate in parallel with permFail. The public
  // hit/miss/fault/paddr response below keeps its existing priority and zeros.
  io.port.candidatePaddr.foreach(_ := Mux(translate, pa, s1Vaddr))
  io.port.resp.valid := s1Valid && !io.port.kill
  io.port.resp.bits := 0.U.asTypeOf(new TlbResp)
  when(s1FromFault) {
    io.port.resp.bits.accessFault := s1FaultIsAccess; io.port.resp.bits.pageFault := !s1FaultIsAccess
  }.elsewhen(!translate) {
    io.port.resp.bits.hit := true.B; io.port.resp.bits.paddr := s1Vaddr
  }.elsewhen(!canonical) { io.port.resp.bits.pageFault := true.B
  }.elsewhen(!anyHit) { io.port.resp.bits.miss := true.B
  }.elsewhen(permFail) { io.port.resp.bits.pageFault := true.B
  }.otherwise { io.port.resp.bits.hit := true.B; io.port.resp.bits.paddr := pa }
  io.observation := 0.U.asTypeOf(io.observation)
  io.walkKilled := missKilled || io.port.kill
  if (instruction) {
    val live = s1Valid && !io.port.kill && !s1FromFault
    io.observation("itlb_tag_hit") := (live && translate && canonical && anyHit).asUInt
    io.observation("itlb_tag_miss") := (live && translate && canonical && !anyHit).asUInt
    io.observation("itlb_bypass") := (live && !translate).asUInt
    io.observation("itlb_fault") := (io.port.resp.valid &&
      (io.port.resp.bits.pageFault || io.port.resp.bits.accessFault)).asUInt
    io.observation("itlb_killed") := (s1Valid && io.port.kill).asUInt
    io.observation("itlb_pending") := missValid.asUInt
  }
  val dropS1Next = s1Valid && io.port.resp.bits.miss
  s1Valid := io.port.req.fire && !dropS1Next
  when(io.port.req.fire) {
    s1Vaddr := io.port.req.bits.vaddr; s1Cmd := io.port.req.bits.cmd
    s1FromFault := pendingFault; s1FaultIsAccess := pendingFaultIsAccess
    when(pendingFault && !io.port.kill) {
      assert(io.port.req.bits.vaddr(38, 12) === missVpn, "fault retry VPN changed"); pendingFault := false.B
    }
    if (instruction) assert(io.port.req.bits.cmd === MmuCmd.Fetch, "iTLB requires Fetch")
    else assert(io.port.req.bits.cmd =/= MmuCmd.Fetch, "dTLB rejects Fetch")
  }
  val hitTouch = io.port.resp.valid && io.port.resp.bits.hit && translate
  if (!deferHitTouch) {
    when(hitTouch) {
      when(baseHit.asUInt.orR) { basePlru(set) := TreePlru.touch(basePlru(set), PriorityEncoder(baseHit), p.ways)
      }.otherwise { spPlru := TreePlru.touch(spPlru, PriorityEncoder(superHit), p.superpages) }
    }
  }
  when(io.port.resp.valid && io.port.resp.bits.miss) {
    missValid := true.B; missGranted := false.B; missKilled := false.B
    missVpn := vpn; missAsid := io.csr.asid; missRootPpn := io.csr.rootPpn
  }
  when(io.grant) { missGranted := true.B }
  when(io.port.kill) {
    if (pipelinedFetchKill) { s1Valid := false.B }
    pendingFault := false.B
    when(missValid) {
      when(missGranted || io.grant) { missKilled := true.B }.otherwise { missValid := false.B }
    }
  }
  when(io.done.valid) {
    assert(missValid && missGranted, "PTW done without granted miss")
    missValid := false.B; missGranted := false.B; missKilled := false.B
    when((io.done.bits.pageFault || io.done.bits.accessFault) && !(missKilled || io.port.kill)) {
      pendingFault := true.B; pendingFaultIsAccess := io.done.bits.accessFault
    }
  }
  val refill = io.done.valid && io.done.bits.refillValid
  val baseWrite = refill && io.done.bits.refill.level === 0.U
  val refillEntry = io.done.bits.refill
  val refillSet = index(refillEntry.vpn)
  if (deferHitTouch) {
    val base = baseHit.asUInt.orR
    // Original last-connect order gives a same-set refill precedence over
    // the current hit. A previous pending touch is already in the view.
    val overwritten = refill && Mux(base, baseWrite && refillSet === set, !baseWrite)
    touchPending := hitTouch && !overwritten
    touchBase := base
    touchSet := set
    touchWay := Mux(base, PriorityEncoder(baseHit), PriorityEncoder(superHit))
  }
  val refillInvalid = VecInit(baseValid(refillSet).map(v => !v))
  val refillVictim = Mux(refillInvalid.asUInt.orR, PriorityEncoder(refillInvalid),
    TreePlru.victim(visibleBasePlru(refillSet), p.ways))
  val refillValue = Wire(new BaseEntry(27 - setBits))
  refillValue.assignPage(refillEntry); refillValue.tag := tag(refillEntry.vpn)
  for (b <- 0 until banks; w <- 0 until p.ways) {
    when(baseWrite && bankOf(refillSet) === b.U && refillVictim === w.U) {
      mem(b)(w).write(rowOf(refillSet), refillValue)
    }
  }
  when(baseWrite) {
    for (s <- 0 until p.sets; w <- 0 until p.ways) {
      when(refillSet === s.U && refillVictim === w.U) { baseValid(s)(w) := true.B }
    }
    basePlru(refillSet) := TreePlru.touch(visibleBasePlru(refillSet), refillVictim, p.ways)
  }
  when(refill && !baseWrite) {
    val invalid = VecInit(sp.map(e => !e.valid))
    val victim = Mux(invalid.asUInt.orR, PriorityEncoder(invalid), TreePlru.victim(visibleSpPlru, p.superpages))
    val target = if (p.superpages == 1) sp(0) else sp(victim)
    target.assignPage(io.done.bits.refill)
    target.vpn := io.done.bits.refill.vpn; target.level := io.done.bits.refill.level
    target.valid := true.B
    spPlru := TreePlru.touch(visibleSpPlru, victim, p.superpages)
  }
  sfS1Valid := sfRead
  when(sfRead) { sfVaddr := io.sfence.vaddr; sfRs2Nz := io.sfence.rs2Nz; sfAsid := io.sfence.asid }
  when(io.sfence.valid && !io.sfence.rs1Nz) {
    for (s <- 0 until p.sets; w <- 0 until p.ways) baseValid(s)(w) := false.B
    for (j <- 0 until p.superpages) sp(j).valid := false.B
  }
  when(sfS1Valid) {
    val sfVpn = sfVaddr(38, 12); val s = index(sfVpn)
    for (i <- 0 until p.ways) {
      when(data(i).tag === tag(sfVpn) && (!sfRs2Nz || (!data(i).g && data(i).asid === sfAsid))) {
        baseValid(s)(i) := false.B
      }
    }
    for (j <- 0 until p.superpages) {
      when(superMatch(sp(j), sfVpn) && (!sfRs2Nz || (!sp(j).g && sp(j).asid === sfAsid))) { sp(j).valid := false.B }
    }
  }
  when(io.port.resp.valid) {
    assert(PopCount(Seq(io.port.resp.bits.hit, io.port.resp.bits.miss,
      io.port.resp.bits.pageFault, io.port.resp.bits.accessFault)) === 1.U, "TLB response must be one-hot")
  }
  when(s1Valid && translate && canonical && !s1FromFault) {
    assert(PopCount(baseHit) +& PopCount(superHit) <= 1.U, "multiple TLB matches")
  }
  assert(!(readEnable && baseWrite), "TLB single-port RAM read/write collision")
  when(io.sfence.valid || sfS1Valid) { assert(!io.port.req.fire && !refill, "sfence conflicts with request/refill") }
  val previousSfence = RegNext(io.sfence.valid, false.B)
  when(io.sfence.valid) {
    assert(io.idle && !s1Valid, "sfence requires a drained TLB")
    assert(!previousSfence && !sfS1Valid, "sfence pulses must be separated")
  }
}

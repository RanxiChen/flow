package flow.mmu.sv39

import chisel3._
import chisel3.util._

/** Two independent S0/S1 query lanes sharing mirrored FF arrays and one refill.
  * A lane owns its miss until PTW done; the other lane remains free to hit. */
class Sv39Tlb(p: Sv39TlbParams, instruction: Boolean) extends Module {
  val io = IO(new Bundle {
    val port = new TlbPortIO; val port2 = new TlbPortIO
    val csr = Input(new MmuCsrIO); val sfence = Input(new SfenceIO)
    val miss = Output(new TlbMiss); val grant = Input(Bool())
    val miss2 = Output(new TlbMiss); val grant2 = Input(Bool())
    val done = Flipped(Valid(new PtwDone)); val done2 = Flipped(Valid(new PtwDone))
    val idle = Output(Bool())
  })
  private val setBits = log2Ceil(p.sets)
  def index(vpn: UInt): UInt = if (p.sets == 1) 0.U else vpn(setBits - 1, 0)
  def tag(vpn: UInt): UInt = vpn >> setBits
  def superMatch(e: SuperEntry, vpn: UInt): Bool =
    Mux(e.level === 2.U, e.vpn(26, 18) === vpn(26, 18), e.vpn(26, 9) === vpn(26, 9))
  val baseCopy0 = RegInit(VecInit(Seq.fill(p.sets)(VecInit(Seq.fill(p.ways)(0.U.asTypeOf(new BaseEntry(27 - setBits)))))))
  val baseCopy1 = RegInit(VecInit(Seq.fill(p.sets)(VecInit(Seq.fill(p.ways)(0.U.asTypeOf(new BaseEntry(27 - setBits)))))))
  val baseValid0 = RegInit(VecInit(Seq.fill(p.sets)(VecInit(Seq.fill(p.ways)(false.B)))))
  val baseValid1 = RegInit(VecInit(Seq.fill(p.sets)(VecInit(Seq.fill(p.ways)(false.B)))))
  val basePlru = RegInit(VecInit(Seq.fill(p.sets)(0.U((p.ways - 1).W))))
  val sp = RegInit(VecInit(Seq.fill(p.superpages)(0.U.asTypeOf(new SuperEntry))))
  val spPlru = RegInit(0.U((p.superpages - 1).W))
  val sfS1Valid = RegInit(false.B)
  val sfVaddr = Reg(UInt(64.W)); val sfRs2Nz = Reg(Bool()); val sfAsid = Reg(UInt(16.W))

  // Instantiate the same registered query and miss ownership logic twice.
  // Replacement updates are combined below, so simultaneous hits are not lost.
  def lane(port: TlbPortIO, copy: Vec[Vec[BaseEntry]], valid: Vec[Vec[Bool]],
      miss: TlbMiss, grant: Bool, done: ValidIO[PtwDone], suffix: String,
      fetchOnly: Boolean): (Bool, Bool, Bool, UInt, UInt, UInt) = {
    val s1Valid = RegInit(false.B).suggestName(s"s1Valid$suffix")
    val s1Vaddr = Reg(UInt(64.W)).suggestName(s"s1Vaddr$suffix")
    val s1Cmd = Reg(MmuCmd()).suggestName(s"s1Cmd$suffix")
    val s1FromFault = Reg(Bool()).suggestName(s"s1FromFault$suffix")
    val s1FaultIsAccess = Reg(Bool()).suggestName(s"s1FaultIsAccess$suffix")
    val missValid = RegInit(false.B).suggestName(s"missValid$suffix")
    val missGranted = RegInit(false.B).suggestName(s"missGranted$suffix")
    val missKilled = RegInit(false.B).suggestName(s"missKilled$suffix")
    val missVpn = Reg(UInt(27.W)).suggestName(s"missVpn$suffix")
    val missAsid = Reg(UInt(16.W)).suggestName(s"missAsid$suffix")
    val missRootPpn = Reg(UInt(44.W)).suggestName(s"missRootPpn$suffix")
    val pendingFault = RegInit(false.B).suggestName(s"pendingFault$suffix")
    val pendingFaultIsAccess = Reg(Bool()).suggestName(s"pendingFaultIsAccess$suffix")
    miss.valid := missValid; miss.granted := missGranted
    miss.vpn := missVpn; miss.asid := missAsid; miss.rootPpn := missRootPpn
    val vpn = s1Vaddr(38, 12); val set = index(vpn); val data = copy(set)
    val baseHit = VecInit((0 until p.ways).map(i => valid(set)(i) && data(i).tag === tag(vpn) &&
      (data(i).g || data(i).asid === io.csr.asid)))
    val superHit = VecInit(sp.map(e => e.valid && superMatch(e, vpn) && (e.g || e.asid === io.csr.asid)))
    val anyHit = baseHit.asUInt.orR || superHit.asUInt.orR
    val entry = Wire(new PageEntry); entry := 0.U.asTypeOf(new PageEntry)
    for (i <- (0 until p.superpages).reverse) when(superHit(i)) { entry.assignPage(sp(i)) }
    for (i <- (0 until p.ways).reverse) when(baseHit(i)) { entry.assignPage(data(i)) }
    val level = Mux(baseHit.asUInt.orR, 0.U, Mux1H(superHit, sp.map(_.level)))
    val effPriv = Mux(s1Cmd =/= MmuCmd.Fetch && io.csr.mprv, io.csr.mpp, io.csr.priv)
    val translate = io.csr.sv39 && effPriv =/= 3.U
    val canonical = !s1Vaddr(63, 38).orR || s1Vaddr(63, 38).andR
    val permFail = !entry.a || (s1Cmd === MmuCmd.Fetch && !entry.x) ||
      (s1Cmd === MmuCmd.Load && !(entry.r || (io.csr.mxr && entry.x))) ||
      (s1Cmd === MmuCmd.Store && !(entry.w && entry.d)) ||
      (effPriv === 0.U && !entry.u) || (effPriv === 1.U && entry.u && (s1Cmd === MmuCmd.Fetch || !io.csr.sum))
    val pa = MuxLookup(level, Cat(entry.ppn, s1Vaddr(11, 0)))(Seq(
      1.U -> Cat(entry.ppn(43, 9), s1Vaddr(20, 0)), 2.U -> Cat(entry.ppn(43, 18), s1Vaddr(29, 0))))
    port.resp.valid := s1Valid && !port.kill
    port.resp.bits := 0.U.asTypeOf(new TlbResp)
    when(s1FromFault) {
      port.resp.bits.accessFault := s1FaultIsAccess; port.resp.bits.pageFault := !s1FaultIsAccess
    }.elsewhen(!translate) {
      port.resp.bits.hit := true.B; port.resp.bits.paddr := s1Vaddr
    }.elsewhen(!canonical) { port.resp.bits.pageFault := true.B
    }.elsewhen(!anyHit) { port.resp.bits.miss := true.B
    }.elsewhen(permFail) { port.resp.bits.pageFault := true.B
    }.otherwise { port.resp.bits.hit := true.B; port.resp.bits.paddr := pa }
    // Preserve the existing miss/retry contract within each lane: a younger
    // request accepted on the miss-detection cycle is dropped and replayed by
    // its client. Neither the ready equation nor that drop crosses lanes.
    port.req.ready := !missValid && !io.sfence.valid && !sfS1Valid && !port.kill
    val dropS1Next = s1Valid && port.resp.bits.miss
    s1Valid := port.req.fire && !dropS1Next
    when(port.req.fire) {
      s1Vaddr := port.req.bits.vaddr; s1Cmd := port.req.bits.cmd
      s1FromFault := pendingFault; s1FaultIsAccess := pendingFaultIsAccess
      when(pendingFault) {
        assert(port.req.bits.vaddr(38, 12) === missVpn, "fault retry VPN changed"); pendingFault := false.B
      }
      if (fetchOnly) assert(port.req.bits.cmd === MmuCmd.Fetch, "iTLB requires Fetch")
      else assert(port.req.bits.cmd =/= MmuCmd.Fetch, "data TLB rejects Fetch")
    }
    when(port.resp.valid && port.resp.bits.miss) {
      missValid := true.B; missGranted := false.B; missKilled := false.B
      missVpn := vpn; missAsid := io.csr.asid; missRootPpn := io.csr.rootPpn
    }
    when(grant) { assert(missValid && !missGranted, "PTW grant without pending miss"); missGranted := true.B }
    when(port.kill) {
      pendingFault := false.B
      when(missValid) {
        when(missGranted || grant) { missKilled := true.B }
          .otherwise { missValid := false.B }
      }
    }
    when(done.valid) {
      assert(missValid && missGranted, "PTW done without granted miss")
      missValid := false.B; missGranted := false.B; missKilled := false.B
      when((done.bits.pageFault || done.bits.accessFault) && !(missKilled || port.kill)) {
        pendingFault := true.B; pendingFaultIsAccess := done.bits.accessFault
      }
    }
    when(port.resp.valid) {
      assert(PopCount(Seq(port.resp.bits.hit, port.resp.bits.miss,
        port.resp.bits.pageFault, port.resp.bits.accessFault)) === 1.U, "TLB response must be one-hot")
    }
    when(s1Valid && translate && canonical && !s1FromFault) {
      assert(PopCount(baseHit) +& PopCount(superHit) <= 1.U, "multiple TLB matches")
    }
    val hit = port.resp.valid && port.resp.bits.hit && translate
    when(io.sfence.valid) { assert(!s1Valid, "sfence requires a drained TLB query lane") }
    (!missValid && !pendingFault,
      hit && baseHit.asUInt.orR, hit && !baseHit.asUInt.orR,
      set, PriorityEncoder(baseHit), PriorityEncoder(superHit))
  }
  val (idle0, baseTouch0, spTouch0, set0, way0, spWay0) =
    lane(io.port, baseCopy0, baseValid0, io.miss, io.grant, io.done, "", instruction)
  val (idle1, baseTouch1, spTouch1, set1, way1, spWay1) =
    lane(io.port2, baseCopy1, baseValid1, io.miss2, io.grant2, io.done2, "2", fetchOnly = false)
  io.idle := idle0 && idle1 && !sfS1Valid
  for (s <- 0 until p.sets) {
    val first = Mux(baseTouch0 && set0 === s.U, TreePlru.touch(basePlru(s), way0, p.ways), basePlru(s))
    when((baseTouch0 && set0 === s.U) || (baseTouch1 && set1 === s.U)) {
      basePlru(s) := Mux(baseTouch1 && set1 === s.U, TreePlru.touch(first, way1, p.ways), first)
    }
  }
  val firstSp = Mux(spTouch0, TreePlru.touch(spPlru, spWay0, p.superpages), spPlru)
  when(spTouch0 || spTouch1) { spPlru := Mux(spTouch1, TreePlru.touch(firstSp, spWay1, p.superpages), firstSp) }

  // The shared PTW produces one completion per cycle, mirrored into both arrays.
  assert(!(io.done.valid && io.done2.valid), "TLB has two simultaneous PTW completions")
  val done = Mux(io.done2.valid, io.done2.bits, io.done.bits)
  val refill = (io.done.valid || io.done2.valid) && done.refillValid
  val baseWrite = refill && done.refill.level === 0.U
  when(baseWrite) {
    val f = done.refill; val s = index(f.vpn)
    val invalid = VecInit(baseValid0(s).map(v => !v))
    // Both lanes can miss on the same page before the first refill. Reuse its
    // entry on the second completion rather than installing duplicate matches.
    val existing = VecInit((0 until p.ways).map(i => baseValid0(s)(i) &&
      baseCopy0(s)(i).tag === tag(f.vpn) && (baseCopy0(s)(i).g || f.g || baseCopy0(s)(i).asid === f.asid)))
    val victim = Mux(existing.asUInt.orR, PriorityEncoder(existing),
      Mux(invalid.asUInt.orR, PriorityEncoder(invalid), TreePlru.victim(basePlru(s), p.ways)))
    for (i <- 0 until p.ways) when(victim === i.U) {
      baseCopy0(s)(i).assignPage(f); baseCopy0(s)(i).tag := tag(f.vpn)
      baseCopy1(s)(i).assignPage(f); baseCopy1(s)(i).tag := tag(f.vpn)
      baseValid0(s)(i) := true.B; baseValid1(s)(i) := true.B
    }
    basePlru(s) := TreePlru.touch(basePlru(s), victim, p.ways)
  }
  when(refill && !baseWrite) {
    val f = done.refill
    val invalid = VecInit(sp.map(e => !e.valid))
    val existing = VecInit(sp.map(e => e.valid && e.level === f.level && superMatch(e, f.vpn) &&
      (e.g || f.g || e.asid === f.asid)))
    val victim = Mux(existing.asUInt.orR, PriorityEncoder(existing),
      Mux(invalid.asUInt.orR, PriorityEncoder(invalid), TreePlru.victim(spPlru, p.superpages)))
    val target = if (p.superpages == 1) sp(0) else sp(victim)
    target.assignPage(f); target.vpn := f.vpn; target.level := f.level; target.valid := true.B
    spPlru := TreePlru.touch(spPlru, victim, p.superpages)
  }
  val sfRead = io.sfence.valid && io.sfence.rs1Nz
  sfS1Valid := sfRead
  when(sfRead) { sfVaddr := io.sfence.vaddr; sfRs2Nz := io.sfence.rs2Nz; sfAsid := io.sfence.asid }
  when(io.sfence.valid && !io.sfence.rs1Nz) {
    for (s <- 0 until p.sets; w <- 0 until p.ways) {
      baseValid0(s)(w) := false.B; baseValid1(s)(w) := false.B
    }
    for (j <- 0 until p.superpages) sp(j).valid := false.B
  }
  when(sfS1Valid) {
    val sfVpn = sfVaddr(38, 12); val s = index(sfVpn)
    for (i <- 0 until p.ways) {
      val e = baseCopy0(s)(i)
      when(e.tag === tag(sfVpn) && (!sfRs2Nz || (!e.g && e.asid === sfAsid))) {
        baseValid0(s)(i) := false.B; baseValid1(s)(i) := false.B
      }
    }
    for (j <- 0 until p.superpages) {
      when(superMatch(sp(j), sfVpn) && (!sfRs2Nz || (!sp(j).g && sp(j).asid === sfAsid))) { sp(j).valid := false.B }
    }
  }
  when(io.sfence.valid || sfS1Valid) {
    assert(!io.port.req.fire && !io.port2.req.fire && !refill, "sfence conflicts with request/refill")
  }
  val previousSfence = RegNext(io.sfence.valid, false.B)
  when(io.sfence.valid) {
    assert(io.idle, "sfence requires a drained TLB")
    assert(!previousSfence && !sfS1Valid, "sfence pulses must be separated")
  }
}

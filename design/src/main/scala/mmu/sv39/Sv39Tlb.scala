package flow.mmu.sv39

import chisel3._
import chisel3.util._

class Sv39Tlb(p: Sv39TlbParams, instruction: Boolean) extends Module {
  val io = IO(new Bundle {
    val port = new TlbPortIO; val csr = Input(new MmuCsrIO); val sfence = Input(new SfenceIO)
    val miss = Output(new TlbMiss); val grant = Input(Bool())
    val done = Flipped(Valid(new PtwDone)); val idle = Output(Bool())
  })
  private val setBits = log2Ceil(p.sets)
  def index(vpn: UInt): UInt = if (p.sets == 1) 0.U else vpn(setBits - 1, 0)
  def tag(vpn: UInt): UInt = vpn >> setBits
  def superMatch(e: SuperEntry, vpn: UInt): Bool =
    Mux(e.level === 2.U, e.vpn(26, 18) === vpn(26, 18), e.vpn(26, 9) === vpn(26, 9))
  val mem = SyncReadMem(p.sets, Vec(p.ways, new BaseEntry(27 - setBits)))
  val baseValid = RegInit(VecInit(Seq.fill(p.sets)(VecInit(Seq.fill(p.ways)(false.B)))))
  val basePlru = RegInit(VecInit(Seq.fill(p.sets)(0.U((p.ways - 1).W))))
  val sp = RegInit(VecInit(Seq.fill(p.superpages)(0.U.asTypeOf(new SuperEntry))))
  val spPlru = RegInit(0.U((p.superpages - 1).W))
  val s1Valid = RegInit(false.B)
  val s1Vaddr = Reg(UInt(64.W)); val s1Cmd = Reg(MmuCmd())
  val s1FromFault = Reg(Bool()); val s1FaultIsAccess = Reg(Bool())
  val missValid = RegInit(false.B); val missGranted = RegInit(false.B); val missKilled = RegInit(false.B)
  val missVpn = Reg(UInt(27.W)); val missAsid = Reg(UInt(16.W)); val missRootPpn = Reg(UInt(44.W))
  val pendingFault = RegInit(false.B); val pendingFaultIsAccess = Reg(Bool())
  val sfS1Valid = RegInit(false.B)
  val sfVaddr = Reg(UInt(64.W)); val sfRs2Nz = Reg(Bool()); val sfAsid = Reg(UInt(16.W))
  io.port.req.ready := !missValid && !io.sfence.valid && !sfS1Valid && !io.port.kill
  io.idle := !missValid && !pendingFault && !sfS1Valid
  io.miss.valid := missValid; io.miss.granted := missGranted
  io.miss.vpn := missVpn; io.miss.asid := missAsid; io.miss.rootPpn := missRootPpn
  val sfRead = io.sfence.valid && io.sfence.rs1Nz
  val readEnable = io.port.req.fire || sfRead
  val readVpn = Mux(sfRead, io.sfence.vaddr(38, 12), io.port.req.bits.vaddr(38, 12))
  val data = mem.read(index(readVpn), readEnable)
  val vpn = s1Vaddr(38, 12); val set = index(vpn)
  val baseHit = VecInit((0 until p.ways).map(i => baseValid(set)(i) && data(i).tag === tag(vpn) &&
    (data(i).g || data(i).asid === io.csr.asid)))
  val superHit = VecInit(sp.map(e => e.valid && superMatch(e, vpn) && (e.g || e.asid === io.csr.asid)))
  val anyHit = baseHit.asUInt.orR || superHit.asUInt.orR
  val entry = Wire(new PageEntry)
  entry := 0.U.asTypeOf(new PageEntry)
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
  val dropS1Next = s1Valid && io.port.resp.bits.miss
  s1Valid := io.port.req.fire && !dropS1Next
  when(io.port.req.fire) {
    s1Vaddr := io.port.req.bits.vaddr; s1Cmd := io.port.req.bits.cmd
    s1FromFault := pendingFault; s1FaultIsAccess := pendingFaultIsAccess
    when(pendingFault) {
      assert(io.port.req.bits.vaddr(38, 12) === missVpn, "fault retry VPN changed"); pendingFault := false.B
    }
    if (instruction) assert(io.port.req.bits.cmd === MmuCmd.Fetch, "iTLB requires Fetch")
    else assert(io.port.req.bits.cmd =/= MmuCmd.Fetch, "dTLB rejects Fetch")
  }
  when(io.port.resp.valid && io.port.resp.bits.hit && translate) {
    when(baseHit.asUInt.orR) { basePlru(set) := TreePlru.touch(basePlru(set), PriorityEncoder(baseHit), p.ways)
    }.otherwise { spPlru := TreePlru.touch(spPlru, PriorityEncoder(superHit), p.superpages) }
  }
  when(io.port.resp.valid && io.port.resp.bits.miss) {
    missValid := true.B; missGranted := false.B; missKilled := false.B
    missVpn := vpn; missAsid := io.csr.asid; missRootPpn := io.csr.rootPpn
  }
  when(io.grant) { missGranted := true.B }
  when(io.port.kill) {
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
  when(baseWrite) {
    val f = io.done.bits.refill; val s = index(f.vpn)
    val invalid = VecInit(baseValid(s).map(v => !v))
    val victim = Mux(invalid.asUInt.orR, PriorityEncoder(invalid), TreePlru.victim(basePlru(s), p.ways))
    val values = Wire(Vec(p.ways, new BaseEntry(27 - setBits)))
    val mask = (0 until p.ways).map(i => victim === i.U)
    for (i <- 0 until p.ways) { values(i).assignPage(f); values(i).tag := tag(f.vpn) }
    mem.write(s, values, mask)
    baseValid(s)(victim) := true.B
    basePlru(s) := TreePlru.touch(basePlru(s), victim, p.ways)
  }
  when(refill && !baseWrite) {
    val invalid = VecInit(sp.map(e => !e.valid))
    val victim = Mux(invalid.asUInt.orR, PriorityEncoder(invalid), TreePlru.victim(spPlru, p.superpages))
    sp(victim).assignPage(io.done.bits.refill)
    sp(victim).vpn := io.done.bits.refill.vpn; sp(victim).level := io.done.bits.refill.level
    sp(victim).valid := true.B
    spPlru := TreePlru.touch(spPlru, victim, p.superpages)
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

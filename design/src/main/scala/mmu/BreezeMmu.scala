package flow.mmu

import chisel3._
import chisel3.util._
import flow.core.PRIV_MODE
import flow.interface._

/** One blocking Sv39 translation unit with private instruction/data TLBs and
  * one shared hardware page-table walker.  The memory port is physical and is
  * intended to be arbitrated onto the coherent L1D path.
  */
class BreezeMmu(val xlen: Int = 64, val entries: Int = 16) extends Module {
  require(xlen == 64, "Sv39 requires RV64")
  require(entries > 0 && (entries & (entries - 1)) == 0,
    "TLB entry count must be a positive power of two")

  val io = IO(new Bundle {
    val i = new BreezeTranslationPort(xlen)
    val d = new BreezeTranslationPort(xlen)
    val context = Input(new BreezeMmuContext(xlen))
    val sfence = Input(new BreezeSfenceReq(xlen))
    val killI = Input(Bool())
    val memReq = Decoupled(new BackendMemReq(xlen))
    val memRsp = Flipped(Decoupled(new BackendMemResp))
  })

  class TlbEntry extends Bundle {
    val valid = Bool()
    val vpn = UInt(27.W)
    val ppn = UInt(44.W)
    val asid = UInt(16.W)
    val global = Bool()
    val level = UInt(2.W)
    val r = Bool(); val w = Bool(); val x = Bool(); val u = Bool()
    val a = Bool(); val d = Bool()
  }

  val itlb = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new TlbEntry))))
  val dtlb = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new TlbEntry))))
  val iReplace = RegInit(0.U(log2Ceil(entries).W))
  val dReplace = RegInit(0.U(log2Ceil(entries).W))

  private def vpnMatch(e: TlbEntry, vpn: UInt): Bool = MuxLookup(e.level, false.B)(Seq(
    0.U -> (e.vpn === vpn),
    1.U -> (e.vpn(26, 9) === vpn(26, 9)),
    2.U -> (e.vpn(26, 18) === vpn(26, 18))))

  private def makePaddr(e: TlbEntry, vaddr: UInt): UInt = {
    val ppn = MuxLookup(e.level, e.ppn)(Seq(
      0.U -> e.ppn,
      1.U -> Cat(e.ppn(43, 9), vaddr(20, 12)),
      2.U -> Cat(e.ppn(43, 18), vaddr(29, 12))))
    Cat(0.U(8.W), ppn, vaddr(11, 0))
  }

  private def permission(e: TlbEntry, access: BreezeMmuAccess.Type,
                         privilege: UInt): Bool = {
    val userOk = Mux(privilege === PRIV_MODE.U.U, e.u,
      Mux(privilege === PRIV_MODE.S.U,
        Mux(access === BreezeMmuAccess.Fetch, !e.u, !e.u || io.context.sum), true.B))
    val accessOk = MuxLookup(access, false.B)(Seq(
      BreezeMmuAccess.Fetch -> e.x,
      BreezeMmuAccess.Load -> (e.r || (io.context.mxr && e.x)),
      BreezeMmuAccess.Store -> e.w))
    userOk && accessOk && e.a && (access =/= BreezeMmuAccess.Store || e.d)
  }

  val effectiveDPriv = Mux(io.context.privilege === PRIV_MODE.M.U && io.context.mprv,
    io.context.mpp, io.context.privilege)
  val satpModeSv39 = io.context.satp(63, 60) === 8.U
  val asid = io.context.satp(59, 44)

  // Exact SFENCE.VMA invalidation, including the global-entry exemption for
  // ASID-specific fences.
  when(io.sfence.valid) {
    for (n <- 0 until entries) {
      val iVpnHit = !io.sfence.useVaddr || vpnMatch(itlb(n), io.sfence.vaddr(38, 12))
      val dVpnHit = !io.sfence.useVaddr || vpnMatch(dtlb(n), io.sfence.vaddr(38, 12))
      val iAsidHit = !io.sfence.useAsid || (!itlb(n).global && itlb(n).asid === io.sfence.asid)
      val dAsidHit = !io.sfence.useAsid || (!dtlb(n).global && dtlb(n).asid === io.sfence.asid)
      when(iVpnHit && iAsidHit) { itlb(n).valid := false.B }
      when(dVpnHit && dAsidHit) { dtlb(n).valid := false.B }
    }
  }

  object State extends ChiselEnum {
    val Idle, Lookup, ReadPteReq, ReadPteWait, CheckPte,
        UpdateAdReq, UpdateAdWait, Respond = Value
  }
  import State._
  val state = RegInit(Idle)
  val reqReg = Reg(new BreezeTranslationReq(xlen))
  val sourceI = RegInit(false.B)
  val killed = RegInit(false.B)
  val level = RegInit(2.U(2.W))
  val tablePpn = Reg(UInt(44.W))
  val pteAddr = Reg(UInt(xlen.W))
  val pte = Reg(UInt(64.W))
  val walkGlobal = RegInit(false.B)
  val resultPaddr = Reg(UInt(xlen.W))
  val resultPageFault = RegInit(false.B)
  val resultAccessFault = RegInit(false.B)

  io.i.req.ready := state === Idle && !io.killI && !io.sfence.valid
  io.d.req.ready := state === Idle && !io.i.req.valid && !io.sfence.valid
  io.i.resp.valid := state === Respond && sourceI && !killed
  io.d.resp.valid := state === Respond && !sourceI && !killed
  for (port <- Seq(io.i.resp, io.d.resp)) {
    port.bits.vaddr := reqReg.vaddr
    port.bits.paddr := resultPaddr
    port.bits.pageFault := resultPageFault
    port.bits.accessFault := resultAccessFault
  }
  io.memReq.valid := false.B
  io.memReq.bits := 0.U.asTypeOf(new BackendMemReq(xlen))
  io.memRsp.ready := state === ReadPteWait || state === UpdateAdWait

  val canonical = reqReg.vaddr(63, 39) === Fill(25, reqReg.vaddr(38))
  val reqPriv = Mux(sourceI, io.context.privilege, effectiveDPriv)
  val translationEnabled = satpModeSv39 && reqPriv =/= PRIV_MODE.M.U
  val reqVpn = reqReg.vaddr(38, 12)
  val iHits = VecInit(itlb.map(e => e.valid && (e.global || e.asid === asid) && vpnMatch(e, reqVpn)))
  val dHits = VecInit(dtlb.map(e => e.valid && (e.global || e.asid === asid) && vpnMatch(e, reqVpn)))
  val hits = Mux(sourceI, iHits.asUInt, dHits.asUInt)
  val hitEntry = Mux(sourceI, Mux1H(iHits, itlb), Mux1H(dHits, dtlb))
  val vpnIndex = MuxLookup(level, reqVpn(8, 0))(Seq(
    2.U -> reqVpn(26, 18), 1.U -> reqVpn(17, 9), 0.U -> reqVpn(8, 0)))
  val walkAddr = Cat(0.U(8.W), tablePpn, 0.U(12.W)) + (vpnIndex << 3)
  // Page-table accesses and final responses use disjoint FSM states. Share
  // the combinational checker without adding translation cycles or scanning
  // PMP entries over time. While a response is stalled, Respond stays active.
  val checkingWalk = state === ReadPteReq || state === UpdateAdReq
  val sharedPmp = Module(new BreezePmpChecker(xlen))
  sharedPmp.io.addr := Mux(checkingWalk,
    Mux(state === UpdateAdReq, pteAddr, walkAddr), resultPaddr)
  sharedPmp.io.sizeLog2 := Mux(checkingWalk, 3.U, reqReg.sizeLog2)
  sharedPmp.io.access := Mux(checkingWalk,
    Mux(state === UpdateAdReq, BreezeMmuAccess.Store, BreezeMmuAccess.Load), reqReg.access)
  sharedPmp.io.privilege := reqPriv
  sharedPmp.io.context := io.context
  io.i.resp.bits.accessFault := resultAccessFault ||
    (!resultPageFault && !sharedPmp.io.allowed)
  io.d.resp.bits.accessFault := resultAccessFault ||
    (!resultPageFault && !sharedPmp.io.allowed)

  when(io.killI && sourceI && state =/= Idle) { killed := true.B }
  when(io.sfence.valid && state =/= Idle) { killed := true.B }

  switch(state) {
    is(Idle) {
      when(io.i.req.fire) {
        reqReg := io.i.req.bits; sourceI := true.B; killed := false.B; state := Lookup
      }.elsewhen(io.d.req.fire) {
        reqReg := io.d.req.bits; sourceI := false.B; killed := false.B; state := Lookup
      }
    }
    is(Lookup) {
      resultPageFault := false.B; resultAccessFault := false.B
      when(!translationEnabled) {
        resultPaddr := reqReg.vaddr; state := Respond
      }.elsewhen(!canonical) {
        resultPaddr := 0.U; resultPageFault := true.B; state := Respond
      }.elsewhen(hits.orR) {
        resultPaddr := makePaddr(hitEntry, reqReg.vaddr)
        resultPageFault := !permission(hitEntry, reqReg.access, reqPriv)
        state := Respond
      }.otherwise {
        level := 2.U
        tablePpn := io.context.satp(43, 0)
        walkGlobal := false.B
        state := ReadPteReq
      }
    }
    is(ReadPteReq) {
      io.memReq.valid := sharedPmp.io.allowed
      io.memReq.bits.valid := true.B
      io.memReq.bits.addr := walkAddr
      io.memReq.bits.sizeLog2 := 3.U
      io.memReq.bits.memOp := BreezeMemOp.Load
      when(!sharedPmp.io.allowed) {
        resultAccessFault := true.B; resultPaddr := 0.U; state := Respond
      }.elsewhen(io.memReq.fire) { pteAddr := walkAddr; state := ReadPteWait }
    }
    is(ReadPteWait) {
      when(io.memRsp.fire) {
        when(io.memRsp.bits.error) {
          resultAccessFault := true.B; resultPaddr := 0.U; state := Respond
        }.otherwise { pte := io.memRsp.bits.data; state := CheckPte }
      }
    }
    is(CheckPte) {
      val v = pte(0); val r = pte(1); val w = pte(2); val x = pte(3)
      val isLeaf = r || x
      val invalid = !v || (!r && w) || pte(63, 54).orR
      val misalignedSuperpage = Mux(level === 2.U, pte(27, 10).orR,
        Mux(level === 1.U, pte(18, 10).orR, false.B))
      val candidate = Wire(new TlbEntry)
      candidate.valid := true.B; candidate.vpn := reqVpn; candidate.ppn := pte(53, 10)
      candidate.asid := asid; candidate.global := walkGlobal || pte(5); candidate.level := level
      candidate.r := r; candidate.w := w; candidate.x := x; candidate.u := pte(4)
      candidate.a := pte(6); candidate.d := pte(7)
      when(invalid || (isLeaf && misalignedSuperpage)) {
        resultPageFault := true.B; resultPaddr := 0.U; state := Respond
      }.elsewhen(isLeaf) {
        val basePermission = {
          val userOk = Mux(reqPriv === PRIV_MODE.U.U, pte(4),
            Mux(reqPriv === PRIV_MODE.S.U,
              Mux(reqReg.access === BreezeMmuAccess.Fetch, !pte(4), !pte(4) || io.context.sum), true.B))
          val accessOk = MuxLookup(reqReg.access, false.B)(Seq(
            BreezeMmuAccess.Fetch -> x,
            BreezeMmuAccess.Load -> (r || (io.context.mxr && x)),
            BreezeMmuAccess.Store -> w))
          userOk && accessOk
        }
        when(!basePermission) {
          resultPageFault := true.B; resultPaddr := 0.U; state := Respond
        }.elsewhen((!pte(6) || (reqReg.access === BreezeMmuAccess.Store && !pte(7))) &&
            !io.context.adue) {
          resultPageFault := true.B; resultPaddr := 0.U; state := Respond
        }.elsewhen(!pte(6) || (reqReg.access === BreezeMmuAccess.Store && !pte(7))) {
          state := UpdateAdReq
        }.otherwise {
          resultPaddr := makePaddr(candidate, reqReg.vaddr)
          when(!killed) {
            when(sourceI) { itlb(iReplace) := candidate; iReplace := iReplace + 1.U }
              .otherwise { dtlb(dReplace) := candidate; dReplace := dReplace + 1.U }
          }
          state := Respond
        }
      }.elsewhen(level === 0.U) {
        resultPageFault := true.B; resultPaddr := 0.U; state := Respond
      }.otherwise {
        tablePpn := pte(53, 10); level := level - 1.U
        walkGlobal := walkGlobal || pte(5)
        state := ReadPteReq
      }
    }
    is(UpdateAdReq) {
      val mask = (1.U(64.W) << 6) |
        Mux(reqReg.access === BreezeMmuAccess.Store, 1.U(64.W) << 7, 0.U)
      io.memReq.valid := sharedPmp.io.allowed
      io.memReq.bits.valid := true.B
      io.memReq.bits.addr := pteAddr
      io.memReq.bits.sizeLog2 := 3.U
      io.memReq.bits.wdata := mask
      io.memReq.bits.memOp := BreezeMemOp.Amo
      io.memReq.bits.amoFunc := BreezeAmoFunc.Or
      when(!sharedPmp.io.allowed) {
        resultAccessFault := true.B; resultPaddr := 0.U; state := Respond
      }.elsewhen(io.memReq.fire) { state := UpdateAdWait }
    }
    is(UpdateAdWait) {
      when(io.memRsp.fire) {
        when(io.memRsp.bits.error) {
          resultAccessFault := true.B; resultPaddr := 0.U; state := Respond
        }.otherwise {
          pte := pte | (1.U(64.W) << 6) |
            Mux(reqReg.access === BreezeMmuAccess.Store, 1.U(64.W) << 7, 0.U)
          state := CheckPte
        }
      }
    }
    is(Respond) {
      when(killed || (sourceI && io.i.resp.fire) || (!sourceI && io.d.resp.fire)) {
        state := Idle
      }
    }
  }
}

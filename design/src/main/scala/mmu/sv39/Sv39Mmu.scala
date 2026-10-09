package flow.mmu.sv39

import chisel3._

class Sv39Mmu(p: Sv39MmuParams = Sv39MmuParams(),
              withPmpCandidate: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val itlb = new TlbPortIO(withPmpCandidate); val dtlb = new TlbPortIO(withPmpCandidate)
    val csr = Input(new MmuCsrIO); val sfence = Input(new SfenceIO)
    val ptwMem = new PtwMemIO; val idle = Output(Bool())
  })
  val itlb = Module(new Sv39Tlb(p.itlb, instruction = true, withPmpCandidate = withPmpCandidate))
  val dtlb = Module(new Sv39Tlb(p.dtlb, instruction = false, withPmpCandidate = withPmpCandidate))
  val wcUpper = Module(new Sv39WalkCache(p.wcUpperSets, p.wcUpperWays, 9))
  val wcMiddle = Module(new Sv39WalkCache(p.wcMiddleSets, p.wcMiddleWays, 18))
  val ptw = Module(new Sv39Ptw)
  itlb.io.port <> io.itlb; dtlb.io.port <> io.dtlb
  for (tlb <- Seq(itlb, dtlb)) { tlb.io.csr := io.csr; tlb.io.sfence := io.sfence }
  ptw.io.iMiss := itlb.io.miss; ptw.io.dMiss := dtlb.io.miss
  itlb.io.grant := ptw.io.iGrant; dtlb.io.grant := ptw.io.dGrant
  itlb.io.done.valid := ptw.io.done.valid && !ptw.io.done.bits.side
  dtlb.io.done.valid := ptw.io.done.valid && ptw.io.done.bits.side
  itlb.io.done.bits := ptw.io.done.bits; dtlb.io.done.bits := ptw.io.done.bits
  wcUpper.io.port <> ptw.io.upper; wcMiddle.io.port <> ptw.io.middle
  wcUpper.io.flush := io.sfence.valid; wcMiddle.io.flush := io.sfence.valid
  io.ptwMem <> ptw.io.mem
  io.idle := itlb.io.idle && dtlb.io.idle && ptw.io.idle
  when(io.sfence.valid) { assert(io.idle, "sfence requires idle MMU") }
}

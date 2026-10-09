package flow.l1i

import chisel3._
import chisel3.util._
import flow.interface._
import flow.mmu.BreezePmpChecker
import flow.mmu.sv39._

/** One outstanding frontend translation; retry a TLB miss after PTW unlocks
  * req.ready. A killed walk is drained by Sv39Mmu, without returning a fetch.
  */
class FetchTlbClient(withPmpCandidate: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new BreezeTranslationReq(64)))
    val response = Decoupled(new BreezeTranslationResp(64))
    val tlb = Flipped(new TlbPortIO(withPmpCandidate))
    val context = Input(new BreezeMmuContext(64))
    val kill = Input(Bool())
    val block = Input(Bool())
  })
  object St extends ChiselEnum { val Idle, Send, Wait, Response = Value }
  val state = RegInit(St.Idle)
  val request = Reg(new BreezeTranslationReq(64))
  val pmpEnd = Reg(new flow.mmu.BreezePmpAccessEnd)
  val result = Reg(new BreezeTranslationResp(64))
  io.request.ready := state === St.Idle && !io.kill && !io.block
  io.response.valid := state === St.Response && !io.kill
  io.response.bits := result
  io.tlb.req.valid := state === St.Send && !io.kill
  io.tlb.req.bits.vaddr := request.vaddr; io.tlb.req.bits.cmd := MmuCmd.Fetch
  io.tlb.kill := io.kill
  val pmp = Module(new BreezePmpChecker(64, decoded = true, precomputedEnd = withPmpCandidate))
  pmp.io.addr := io.tlb.candidatePaddr.getOrElse(io.tlb.resp.bits.paddr)
  pmp.io.sizeLog2 := request.sizeLog2
  pmp.io.accessEnd.foreach(_ := pmpEnd)
  pmp.io.access := BreezeMmuAccess.Fetch; pmp.io.privilege := io.context.privilege
  pmp.io.context := io.context
  when(io.request.fire) {
    assert(io.request.bits.access === BreezeMmuAccess.Fetch)
    request := io.request.bits; state := St.Send
    pmpEnd := flow.mmu.BreezePmpDecode.accessEnd(io.request.bits.vaddr(6, 0), io.request.bits.sizeLog2)
  }
  when(io.tlb.req.fire) { state := St.Wait }
  when(state === St.Wait && io.tlb.resp.valid) {
    when(io.tlb.resp.bits.miss) { state := St.Send }
      .otherwise {
        result.vaddr := request.vaddr; result.paddr := io.tlb.resp.bits.paddr
        result.pageFault := io.tlb.resp.bits.pageFault
        result.accessFault := io.tlb.resp.bits.accessFault || (io.tlb.resp.bits.hit && !pmp.io.allowed)
        state := St.Response
      }
  }
  when(io.response.fire || io.kill) { state := St.Idle }
}

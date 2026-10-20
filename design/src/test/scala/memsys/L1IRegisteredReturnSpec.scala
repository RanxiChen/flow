package flow.memsys

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface._
import flow.l1i.L1IClient
import flow.frontend.BreezeFrontendMmuHarness
import org.scalatest.freespec.AnyFreeSpec

class RegisteredIRefillHarness extends Module {
  val g = BreezeMemGeometry.singleCore
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new BreezeCacheReqIO(64)))
    val response = Decoupled(new BreezeCacheRespIO(64, 32))
    val flush = Input(Bool())
    val coh = new ReadClientIO(CoherenceParams(g))
    val returned = Output(Bool())
  })
  val frontend = Module(new BreezeFrontendMmuHarness(g))
  val client = Module(new L1IClient(g))
  val active = RegInit(false.B)
  val pc = Reg(UInt(64.W))
  io.request.ready := !active
  when(io.request.fire) { active := true.B; pc := io.request.bits.vaddr }
  when(io.response.fire || io.flush) { active := false.B }
  frontend.io.resetPc := 0x80000000L.U
  frontend.io.context := 0.U.asTypeOf(frontend.io.context)
  frontend.io.context.privilege := 3.U
  frontend.io.context.mpp := 3.U
  frontend.io.context.pmpRangesValid := true.B
  frontend.io.block := !active
  frontend.io.redirect.valid := io.flush || io.request.fire
  frontend.io.redirect.bits := Mux(io.request.fire, io.request.bits.vaddr, pc)
  frontend.io.fenceI := io.flush
  frontend.io.sfence := 0.U.asTypeOf(frontend.io.sfence)
  frontend.io.resolve.valid := false.B
  frontend.io.resolve.bits := 0.U.asTypeOf(frontend.io.resolve.bits)
  frontend.io.committedBranch := 0.U.asTypeOf(frontend.io.committedBranch)
  frontend.io.ptwMem.req.ready := true.B
  frontend.io.ptwMem.resp.valid := false.B
  frontend.io.ptwMem.resp.bits := 0.U.asTypeOf(frontend.io.ptwMem.resp.bits)
  io.response.valid := active && frontend.io.instruction.valid
  frontend.io.instruction.ready := active && io.response.ready
  io.response.bits := 0.U.asTypeOf(io.response.bits)
  io.response.bits.data := frontend.io.instruction.bits.raw
  io.response.bits.vaddr := frontend.io.instruction.bits.pc
  io.response.bits.accessFault := frontend.io.instruction.bits.accessFault
  io.response.bits.pageFault := frontend.io.instruction.bits.pageFault
  client.io.demand <> frontend.io.refillReq
  frontend.io.refillRsp <> client.io.demandRsp
  client.io.prefetch.valid := false.B; client.io.prefetch.bits := 0.U
  io.coh <> client.io.coh
  io.returned := client.io.demandRsp.vld
}

class L1IRegisteredReturnSpec extends AnyFreeSpec with ChiselSim {
  for (flushOffset <- Seq(0, 1)) {
    s"SOC3d flush at registered refill offset $flushOffset drains once without installing stale data" in {
      simulate(new RegisteredIRefillHarness) { d =>
        d.io.request.valid.poke(false.B); d.io.request.bits.vaddr.poke(0.U)
        d.io.request.bits.paddr.poke(0.U); d.io.response.ready.poke(true.B)
        d.io.flush.poke(false.B); d.io.coh.req.ready.poke(true.B)
        d.io.coh.rspDown.valid.poke(false.B); d.io.coh.rspDown.bits.id.poke(0.U)
        d.io.coh.rspDown.bits.op.poke(RspDownOp.ReadData)
        d.io.coh.rspDown.bits.error.poke(false.B); d.io.coh.rspDown.bits.data.poke(0.U)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        def miss(): Unit = {
          d.io.request.ready.expect(true.B)
          d.io.request.bits.vaddr.poke(0x80000000L.U); d.io.request.bits.paddr.poke(0x80000000L.U)
          d.io.request.valid.poke(true.B); d.clock.step(); d.io.request.valid.poke(false.B)
          var guard = 0
          while (!d.io.coh.req.valid.peek().litToBoolean && guard < 30) {
            d.io.response.valid.expect(false.B); d.clock.step(); guard += 1
          }
          assert(guard < 30, "expected an external miss, not a stale cache hit")
          d.io.coh.req.bits.id.expect(0.U); d.clock.step()
        }
        miss()
        d.io.coh.rspDown.valid.poke(true.B); d.io.coh.rspDown.bits.data.poke(0x11110093.U)
        d.io.flush.poke((flushOffset == 0).B)
        d.io.returned.expect(false.B); d.clock.step()
        d.io.coh.rspDown.valid.poke(false.B); d.io.flush.poke((flushOffset == 1).B)
        d.io.returned.expect(true.B); d.io.response.valid.expect(false.B); d.clock.step()
        d.io.flush.poke(false.B); d.io.returned.expect(false.B)
        for (_ <- 0 until 4) { d.io.response.valid.expect(false.B); d.clock.step() }
        miss()
        d.io.coh.rspDown.valid.poke(true.B); d.io.coh.rspDown.bits.data.poke(0x22220093.U)
        d.clock.step(); d.io.coh.rspDown.valid.poke(false.B)
        d.io.response.valid.expect(false.B); d.clock.step()
        // Preserve the strict registered client boundary above. New F0/F1/F2
        // instruction delivery has its own bounded latency, not the deleted
        // cache's legacy internal stage count. Architectural values unchanged.
        var responseGuard = 0
        while(!d.io.response.valid.peek().litToBoolean && responseGuard < 8) {
          d.clock.step(); responseGuard += 1
        }
        d.io.response.valid.expect(true.B); d.io.response.bits.data.expect(0x22220093.U)
        d.io.response.bits.vaddr.expect(0x80000000L.U)
        d.io.response.bits.accessFault.expect(false.B); d.clock.step(2)
        d.io.response.valid.expect(false.B)
      }
    }
  }
}

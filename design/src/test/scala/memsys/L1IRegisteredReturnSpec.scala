package flow.memsys

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface._
import flow.l1i.{L1ICache, L1IClient, L1IParams}
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
  val cache = Module(new L1ICache(L1IParams(g)))
  val client = Module(new L1IClient(g))
  cache.io.dreq <> io.request; io.response <> cache.io.drsp
  cache.io.flush := io.flush
  client.io.demand <> cache.io.next_level_req
  cache.io.next_level_rsp <> client.io.demandRsp
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
        d.io.response.valid.expect(true.B); d.io.response.bits.data.expect(0x22220093.U)
        d.io.response.bits.accessFault.expect(false.B); d.clock.step(2)
        d.io.response.valid.expect(false.B)
      }
    }
  }
}

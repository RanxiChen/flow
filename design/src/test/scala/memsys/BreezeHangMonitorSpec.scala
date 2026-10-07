package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.top.BreezeHangMonitor
import org.scalatest.freespec.AnyFreeSpec

class BreezeHangMonitorSpec extends AnyFreeSpec with ChiselSim {
  private def init(d: BreezeHangMonitor): Unit = {
    d.io.retire.poke(true.B)
    d.io.stalled.foreach(_.poke(false.B))
    d.io.request.foreach(_.poke(false.B)); d.io.complete.foreach(_.poke(false.B))
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  "no retirement triggers exactly at threshold and stays sticky after progress" in {
    simulate(new BreezeHangMonitor(4)) { d =>
      init(d); d.io.retire.poke(false.B)
      d.clock.step(3); d.io.hang.expect(false.B)
      d.clock.step(); d.io.reasons.expect(1.U); d.io.noRetireCycles.expect(4.U)
      d.io.retire.poke(true.B); d.clock.step(); d.io.hang.expect(true.B)
      d.io.noRetireCycles.expect(0.U)
    }
  }
  "each stalled channel independently triggers without changing traffic inputs" in {
    for (channel <- 0 until 10) simulate(new BreezeHangMonitor(4)) { d =>
      init(d); d.io.stalled(channel).poke(true.B)
      d.clock.step(3); d.io.hang.expect(false.B)
      d.clock.step(); d.io.reasons.expect((BigInt(1) << (channel + 1)).U)
      d.io.stalled(channel).expect(true.B)
      d.io.stalled(channel).poke(false.B); d.clock.step(); d.io.hang.expect(true.B)
    }
  }
  "each accepted request without response times out" in {
    for (channel <- 0 until 4) simulate(new BreezeHangMonitor(4)) { d =>
      init(d); d.io.request(channel).poke(true.B); d.clock.step()
      d.io.request(channel).poke(false.B); d.clock.step(3); d.io.hang.expect(false.B)
      d.clock.step(); d.io.reasons.expect((BigInt(1) << (11 + channel)).U)
    }
  }
  "completing an older read retains the younger read age" in {
    simulate(new BreezeHangMonitor(5)) { d =>
      init(d); d.io.request(0).poke(true.B); d.clock.step(2)
      d.io.request(0).poke(false.B); d.clock.step(2)
      d.io.complete(0).poke(true.B); d.clock.step(); d.io.complete(0).poke(false.B)
      d.io.hang.expect(false.B); d.clock.step(2)
      d.io.reasons.expect((BigInt(1) << 11).U)
    }
  }
  "normal traffic and short stalls never trigger" in {
    simulate(new BreezeHangMonitor(5)) { d =>
      init(d)
      for (_ <- 0 until 8) {
        d.io.retire.poke(false.B); d.io.stalled.foreach(_.poke(true.B))
        d.io.request.foreach(_.poke(true.B)); d.clock.step()
        d.io.request.foreach(_.poke(false.B)); d.clock.step(2)
        d.io.complete.foreach(_.poke(true.B)); d.io.retire.poke(true.B)
        d.io.stalled.foreach(_.poke(false.B)); d.clock.step()
        d.io.complete.foreach(_.poke(false.B)); d.io.hang.expect(false.B)
      }
    }
  }
}

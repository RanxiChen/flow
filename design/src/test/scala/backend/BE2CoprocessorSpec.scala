package flow.backend

import chisel3._
import flow.config.{BackendConfig, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BE2CoprocessorSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import Instructions._
  "N02 copro delayed Ok replays once then commits once in program order" in {
    simulate(new BreezeBackend(BackendConfig(privilegeProfile=PrivilegeProfile.Linux, coprocessorStub=Some(7)))) { d =>
      val m=new Environment(d); m.reset()
      val p=m.run(Seq(addi(1,0,7),BigInt(0x0b),addi(2,1,1)),40)
      for(_ <- 0 until 6) m.step()
      m.events.count(e=>e.kind=="commit" && e.pc==p(1)) mustBe 1
      m.events.count(e=>e.kind=="replay" && e.pc==p(1)) mustBe 1
      m.events.filter(_.kind=="commit").map(_.pc).toVector mustBe
        (0 until ((m.pc-BigInt(0x400))/4).toInt).map(i=>BigInt(0x400)+i*4).toVector
      m.writes(2).map(_.data).toVector mustBe Vector(BigInt(8))
      d.io.observe.gprBusy.expect(0.U)
    }
  }
  "N02 copro Exc discards younger accepted MUL and retains older architectural writes" in {
    simulate(new BreezeBackend(BackendConfig(privilegeProfile=PrivilegeProfile.Linux, coprocessorStub=Some(7)))) { d =>
      val m=new Environment(d); m.reset()
      m.issue(addi(1,0,7)); for(_ <- 0 until 6) m.step()
      val c=m.issue(BigInt(0x100b)); val young=m.issue(mdu(2,1,1))
      for(_ <- 0 until 80) m.step()
      m.events.filter(e=>e.kind=="commit" && (e.pc==c || e.pc==young)) mustBe empty
      m.writes(1).map(_.data).toVector mustBe Vector(BigInt(7))
      m.writes(2) mustBe empty
      m.events.count(e=>e.kind=="replay" && e.pc==c) mustBe 1
      m.events.filter(e=>e.kind=="redirect" && e.data==0).size mustBe 1
      d.io.observe.gprBusy.expect(0.U)
    }
  }
  "R2 copro kill hides a decision on the kill edge and returns capacity once" in {
    simulate(new CoprocessorStub(1)) { d =>
      d.io.req.valid.poke(false.B); d.io.req.bits.poke(0x0b.U)
      d.io.commitD.poke(false.B); d.io.killD.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.req.valid.poke(true.B); d.clock.step(); d.io.req.valid.poke(false.B)
      d.clock.step(2); d.io.decision.valid.expect(true.B)
      d.io.killD.poke(true.B); d.io.decision.valid.expect(false.B); d.io.returned.expect(true.B)
      d.clock.step(); d.io.killD.poke(false.B)
      d.io.returned.expect(false.B); d.io.decision.valid.expect(false.B); d.io.drained.expect(true.B)
    }
  }
}

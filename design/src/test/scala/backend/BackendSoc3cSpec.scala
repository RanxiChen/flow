package flow.backend

import chisel3._
import chisel3.util._
import flow.config.{BackendConfig, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BackendSoc3cSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import Instructions._
  private def check(f: Environment => Unit): Unit =
    simulate(new BreezeBackend(BackendConfig(privilegeProfile = PrivilegeProfile.Linux), enabledebug = true)) { d =>
      val m = new Environment(d); m.reset(); f(m)
    }
  "D1 branch advances and redirects once while an older committed miss is outstanding" in { check { m =>
    m.returnDelay=40; m.misses += BigInt(0)
    m.issue(ld(1,0)); m.issue(nop); val branch=m.issue(BigInt(0x463))
    val wrong=branch+4; val correct=branch+8
    m.step(Some(wrong->addi(9,0,99))) mustBe false
    m.pc=correct; m.issue(addi(5,0,55))
    for (_ <- 0 until 65) m.step()
    m.all("redirect").size mustBe 1
    m.events.find(_.kind=="redirect").get.data mustBe correct
    m.at("ex",branch) mustBe 3
    m.at("ex",correct) mustBe 5
    m.events.count(e=>e.kind=="commit" && e.pc==branch) mustBe 1
    m.writes(9) mustBe empty
    m.writes(5).map(_.data).toSeq mustBe Seq(BigInt(55))
    m.writes(1).map(_.data).toSeq mustBe Seq(BigInt(0x1234))
  }}
  "D1 older WB fault overrides an earlier EX redirect without committing the branch" in { check { m =>
    m.faults += BigInt(0)
    m.issue(ld(1,0)); m.issue(nop); val branch=m.issue(BigInt(0x463))
    for (_ <- 0 until 18) m.step()
    m.all("redirect") mustBe Seq(3,4)
    m.events.filter(_.kind=="redirect").map(_.data).toSeq mustBe Seq(branch+8,BigInt(0x200))
    m.events.filter(e=>e.kind=="commit" && e.pc==branch) mustBe empty
    // EX trains at 3; the registered BTB output exposes it at 4. The WB
    // kill edge at 4 creates no new training, so there is no output at 5.
    m.all("btb") mustBe Seq(4)
    m.d.io.observe.gprBusy.expect(0.U)
  }}
  "C1 FP request fires once and advances while an older committed miss is outstanding" in { check { m =>
    m.enableFp(); m.returnDelay=30; m.misses += BigInt(0)
    m.issue(ld(1,0)); m.issue(nop); val fpPc=m.issue(fp(2))
    for (_ <- 0 until 65) m.step()
    m.events.filter(e=>e.kind=="fpIn" && e.pc==fpPc).size mustBe 1
    m.at("fpIn",fpPc) mustBe m.at("ex",fpPc)
    m.at("ex",fpPc) must be < m.all("late").head
    m.writes(2,true).size mustBe 1
  }}

}

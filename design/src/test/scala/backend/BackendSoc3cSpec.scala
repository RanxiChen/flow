package flow.backend

import chisel3._
import flow.config.{BackendConfig, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BackendSoc3cSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import Instructions._
  private def check(f: Environment => Unit): Unit =
    simulate(new BreezeBackend(BackendConfig(privilegeProfile = PrivilegeProfile.Linux), enabledebug = true)) { d =>
      val m = new Environment(d); m.reset(); f(m)
    }
  "D1 held EX branch survives its early redirect and advances correct-path instructions once" in { check { m =>
    m.holdUntil = 10; m.misses += BigInt(0)
    m.issue(ld(1, 0)); m.issue(nop); val branch = m.issue(BigInt(0x463))
    val wrong = branch + 4; val correct = branch + 8
    m.step(Some(wrong -> addi(9, 0, 99))) mustBe false
    m.all("redirect").size mustBe 1
    while (m.cycle < 10) {
      m.d.io.debug.get.idExeValid.expect(true.B)
      m.d.io.debug.get.idExePc.expect(branch.U)
      m.step(Some(correct -> addi(5, 0, 55))) mustBe false
    }
    m.step(Some(correct -> addi(5, 0, 55))) mustBe true
    for (_ <- 0 until 65) m.step()
    m.all("redirect").size mustBe 1
    m.events.find(_.kind == "redirect").get.data mustBe correct
    m.at("ex", branch) mustBe 10
    m.at("ex", correct) mustBe 11
    m.events.count(e => e.kind == "commit" && e.pc == branch) mustBe 1
    m.writes(9) mustBe empty
    m.writes(5).map(_.data).toSeq mustBe Seq(BigInt(55))
  }}
  "D1 older WB fault overrides an early EX redirect without committing the branch" in { check { m =>
    m.holdUntil = 10; m.faults += BigInt(0)
    m.issue(ld(1, 0)); m.issue(nop); val branch = m.issue(BigInt(0x463))
    for (_ <- 0 until 18) m.step()
    m.all("redirect") mustBe Seq(3, 10)
    m.events.filter(_.kind == "redirect").map(_.data).toSeq mustBe Seq(branch + 8, BigInt(0))
    m.events.filter(e => e.kind == "commit" && e.pc == branch) mustBe empty
    m.all("btb") mustBe empty
    m.d.io.observe.gprBusy.expect(0.U)
  }}
  "C1 held EX FP request fires only once before older memory releases" in { check { m =>
    m.enableFp()
    m.holdUntil = m.cycle + 12
    val release = m.holdUntil
    m.issue(ld(1, 0)); m.issue(nop); val fpPc = m.issue(fp(2))
    for (_ <- 0 until 30) m.step()
    m.events.filter(e => e.kind == "fpIn" && e.pc == fpPc).size mustBe 1
    m.at("fpIn", fpPc) must be < release
    m.at("ex", fpPc) mustBe release
    m.writes(2, true).size mustBe 1
  }}
}

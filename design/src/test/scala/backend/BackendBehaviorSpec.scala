package flow.backend

import chisel3._
import flow.config.{BackendConfig, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Architectural regressions use the native L1D contract model directly. */
class BackendBehaviorSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import Instructions._
  private def check(f: Environment => Unit): Unit = {
    simulate(new BreezeBackend(BackendConfig(privilegeProfile = PrivilegeProfile.Linux), enabledebug = true)) { d =>
      val m = new Environment(d); m.reset(); f(m)
    }
  }
  private def branch(taken: Boolean): BigInt = if(taken) BigInt(0x463) else BigInt(0x1463) // beq/bne x0,x0,+8
  for ((actual, predicted) <- Seq(true->false, false->true, false->false)) {
    s"branch outcome=$actual prediction=$predicted trains once and selects its architectural target" in { check { m =>
      m.predictionTaken=predicted; m.predictionTarget=Some(m.pc+8); m.predictionIndex=7
      val pc=m.issue(branch(actual)); for(_ <- 0 until 12) m.step()
      m.all("pht").size mustBe 1; m.all("ghr").size mustBe 1; m.all("btb").size mustBe 1
      m.events.find(_.kind=="pht").get.rd mustBe 7
      m.events.find(_.kind=="pht").get.data mustBe (if(actual) BigInt(1) else BigInt(0))
      m.events.find(_.kind=="btb").get.data mustBe pc+8
      m.all("redirect").size mustBe (if(actual!=predicted) 1 else 0)
      if(actual!=predicted) m.events.find(_.kind=="redirect").get.data mustBe pc+(if(actual) 8 else 4)
    }}
  }
  for ((target, predicted) <- Seq(BigInt(0x80)->BigInt(0x40),BigInt("3f92bffbfe",16)->BigInt(0x40),BigInt(0x80)->BigInt(0x80))) {
    s"JALR target ${target.toString(16)} with prediction ${predicted.toString(16)} preserves all 64 bits" in { check { m =>
      m.values(0)=target; m.run(Seq(ld(1,0)),5)
      m.predictionTaken=true; m.predictionTarget=Some(predicted)
      m.issue(BigInt(0x8067)); for(_ <- 0 until 12) m.step()
      if(target!=predicted) {
        m.events.filter(_.kind=="redirect").map(_.data).toSeq mustBe Seq(target)
        m.events.filter(_.kind=="btb").map(_.data).toSeq mustBe Seq(target)
      } else { m.all("redirect") mustBe empty; m.all("btb") mustBe empty }
      m.all("pht") mustBe empty; m.all("ghr") mustBe empty
    }}
  }
  "FP with FS Off traps without issuing or writing an FP operation" in { check { m =>
    m.issue(fp(3,1,2)); for(_ <- 0 until 20) m.step()
    m.all("fpIn") mustBe empty; m.writes(3,true) mustBe empty
    m.d.io.debug.get.csrMcause.expect(2.U)
  }}
  "unaligned FP64 address reaches L1D and its alignment exception is propagated" in { check { m =>
    m.enableFp(); m.faults += BigInt(3); m.faultCause=4; m.issue(ld(1,3,true))
    m.d.io.l1d.req.valid.expect(true.B); m.d.io.l1d.req.bits.vaddr.expect(3.U)
    for(_ <- 0 until 20) m.step()
    m.all("req").size mustBe 1; m.writes(1,true) mustBe empty
    m.d.io.debug.get.csrMcause.expect(4.U)
  }}
  "FP load and store carry width, destination bank and NaN-boxing request on native L1D" in { check { m =>
    m.enableFp()
    val flw=(BigInt(2)<<12)|(BigInt(3)<<7)|7
    m.issue(flw)
    m.d.io.l1d.req.valid.expect(true.B); m.d.io.l1d.req.bits.size.expect(2.U)
    m.d.io.l1d.req.bits.rd.isFp.expect(true.B); m.d.io.l1d.req.bits.isFlw.expect(true.B)
    for(_ <- 0 until 8) m.step()
    val fsd=(BigInt(3)<<20)|(BigInt(3)<<12)|0x27
    m.issue(fsd)
    m.d.io.l1d.req.valid.expect(true.B); m.d.io.l1d.req.bits.op.expect(L1DOp.Store)
    m.d.io.l1d.req.bits.size.expect(3.U)
    for(_ <- 0 until 8) m.step()
  }}
  for (second <- Seq(false,true)) {
    s"fetch fault secondParcel=$second keeps EPC at instruction start and selects access before page fault" in { check { m =>
      m.instructionAccessFault=true; m.instructionPageFault=true; m.faultSecondParcel=second
      val pc=m.issue(nop); m.instructionAccessFault=false; m.instructionPageFault=false
      for(_ <- 0 until 20) m.step()
      m.d.io.debug.get.csrMcause.expect(1.U); m.d.io.debug.get.csrMepc.expect(pc.U)
      m.run(Seq(csr(6,0x343)),12)
      m.writes(6).last.data mustBe pc+(if(second) 2 else 0)
    }}
  }
}

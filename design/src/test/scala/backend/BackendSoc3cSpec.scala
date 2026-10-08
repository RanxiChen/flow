package flow.backend

import chisel3._
import chisel3.util._
import flow.config.{BackendConfig, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import flow.frontend.BreezeFrontendBoundary
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Production skid and backend connection; memory replies remain independent stimuli. */
class Soc3cSkidBackendFixture extends Module {
  val backend = Module(new BreezeBackend(BackendConfig(privilegeProfile = PrivilegeProfile.Linux), enabledebug = true))
  val boundary = Module(new BreezeFrontendBoundary(backend.cfg.ghrLength))
  val io = IO(chiselTypeOf(backend.io))
  backend.io <> io
  boundary.io.fast := backend.io.frontendFastRedirect
  boundary.io.slow := backend.io.frontendSlowRedirect
  boundary.io.in <> io.fetchBuffer
  backend.io.fetchBuffer <> boundary.io.out
}

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
    // Linux CSRFile initializes mtvec to 0x200; this case does not write it.
    m.events.filter(_.kind == "redirect").map(_.data).toSeq mustBe Seq(branch + 8, BigInt(0x200))
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

  "D1 production skid retains corrected instructions while the backend holds EX" in {
    simulate(new Soc3cSkidBackendFixture) { d =>
      d.io.resetAddr.poke(0x400.U)
      d.io.machineTimerInterrupt.poke(false.B); d.io.machineSoftwareInterrupt.poke(false.B)
      d.io.time.poke(0.U); d.io.externalInterrupts.poke(0.U); d.io.supervisorExternalInterrupt.poke(false.B)
      d.io.mmuIdle.poke(true.B)
      // All HPM inputs are independent and idle for this functional scenario.
      d.io.hpmEvents.controlRetired.poke(false.B); d.io.hpmEvents.controlTaken.poke(false.B)
      d.io.hpmEvents.predictionMiss.poke(false.B); d.io.hpmEvents.icacheAccess.poke(false.B)
      d.io.hpmEvents.icacheMiss.poke(false.B); d.io.hpmEvents.dcacheAccess.poke(false.B)
      d.io.hpmEvents.dcacheMiss.poke(false.B); d.io.hpmEvents.dcacheUncached.poke(false.B)
      d.io.hpmEvents.memStallCycle.poke(false.B); d.io.hpmEvents.loadUseStall.poke(false.B)
      d.io.hpmEvents.mulSourceStall.poke(false.B); d.io.hpmEvents.divSourceStall.poke(false.B)
      d.io.hpmEvents.wbPortConflict.poke(0.U)
      d.io.fetchBuffer.valid.poke(false.B)
      d.io.l1d.req.ready.poke(true.B); d.io.l1d.s2Hold.poke(false.B)
      d.io.l1d.resp.valid.poke(false.B); d.io.l1d.resp.bits.kind.poke(L1DRespKind.Mshr)
      d.io.l1d.resp.bits.data.poke(0.U); d.io.l1d.resp.bits.excCause.poke(0.U); d.io.l1d.resp.bits.tval.poke(0.U)
      d.io.l1d.late.valid.poke(false.B); d.io.l1d.late.bits.rd.idx.poke(1.U)
      d.io.l1d.late.bits.rd.isFp.poke(false.B); d.io.l1d.late.bits.data.poke(0x1234.U)
      d.io.l1d.late.bits.error.poke(false.B); d.io.l1d.drained.poke(false.B); d.io.l1d.mmioBusy.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      var cycle=0
      val exPcs=scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val commits=scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val writes=scala.collection.mutable.ArrayBuffer.empty[(Int,BigInt)]
      val redirects=scala.collection.mutable.ArrayBuffer.empty[BigInt]
      def step(instruction: Option[(BigInt,BigInt)] = None): Boolean = {
        d.io.fetchBuffer.valid.poke(instruction.nonEmpty.B)
        instruction.foreach { case (pc,inst) =>
          val b=d.io.fetchBuffer.bits
          b.pc.poke(pc.U); b.inst.poke(inst.U); b.rawInst.poke(inst.U); b.instLen.poke(4.U)
          b.isCompressed.poke(false.B); b.illegalCompressed.poke(false.B)
          b.instructionAccessFault.poke(false.B); b.instructionPageFault.poke(false.B)
          b.instructionFaultSecondParcel.poke(false.B)
          b.pred.predType.poke(if((inst & 0x7f)==0x63) FrontendPredType.BR else FrontendPredType.NONE)
          b.pred.predTaken.poke(false.B); b.pred.predPc.poke((pc+4).U); b.pred.phtIdx.poke(0.U)
        }
        // Load accepted at E=1 reaches S2 at 3, holds through 9,
        // allocates the miss at 10 and returns independently at 30.
        val held=cycle>=3 && cycle<10
        d.io.l1d.s2Hold.poke(held.B); d.io.l1d.req.ready.poke((!held).B)
        d.io.l1d.resp.valid.poke((cycle==10).B)
        d.io.l1d.late.valid.poke((cycle==30).B)
        val accepted=instruction.nonEmpty && d.io.fetchBuffer.ready.peek().litToBoolean
        val o=d.io.observe
        if(o.exFire.peek().litToBoolean) exPcs += o.exPc.peek().litValue
        if(o.commit.peek().litToBoolean) commits += o.commitPc.peek().litValue
        if(o.gprWrite.valid.peek().litToBoolean) writes += ((o.gprWrite.bits.idx.peek().litValue.toInt,o.gprWrite.bits.data.peek().litValue))
        if(d.io.frontendFastRedirect.valid.peek().litToBoolean) redirects += d.io.frontendFastRedirect.target.peek().litValue
        d.clock.step(); cycle+=1; accepted
      }
      step(Some(BigInt(0x400)->ld(1,0))) mustBe true
      step(Some(BigInt(0x404)->nop)) mustBe true
      step(Some(BigInt(0x408)->BigInt(0x463))) mustBe true
      step(Some(BigInt(0x40c)->addi(9,0,99))) // Wrong path is flushed at the fast redirect edge.
      step(Some(BigInt(0x410)->addi(5,0,55))) mustBe true
      step(Some(BigInt(0x414)->addi(6,0,66))) mustBe true
      d.io.fetchBuffer.ready.expect(false.B)
      while(cycle<10) {
        d.io.debug.get.idExeValid.expect(true.B); d.io.debug.get.idExePc.expect(0x408.U)
        step()
      }
      while(cycle<45) step()
      redirects.toSeq mustBe Seq(BigInt(0x410))
      exPcs.toSeq mustBe Seq(0x400,0x404,0x408,0x410,0x414).map(BigInt(_))
      commits.toSeq mustBe Seq(0x400,0x404,0x408,0x410,0x414).map(BigInt(_))
      writes.filter(_._1==9) mustBe empty
      writes.filter(_._1==5).toSeq mustBe Seq(5->BigInt(55))
      writes.filter(_._1==6).toSeq mustBe Seq(6->BigInt(66))
      writes.filter(_._1==1).toSeq mustBe Seq(1->BigInt(0x1234))
      d.io.observe.gprBusy.expect(0.U)
    }
  }
}

package flow.backend

import chisel3._
import flow.config.{BackendConfig, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** The G2/N03 contract explicitly permits injection of ordinary W2 writes.
  * Injection changes only the existing committed-result capture, not any
  * issue, commit, arithmetic, credit or kill decision.
  */
class BE2W2StorageProbe extends BreezeBackend(BackendConfig(privilegeProfile = PrivilegeProfile.Linux)) {
  val occupyW2 = IO(Input(Bool()))
  writeback.io.ordinary.valid := wbOrdinary || occupyW2
  when(occupyW2) {
    writeback.io.ordinary.bits.rd.idx := 31.U
    writeback.io.ordinary.bits.rd.isFp := false.B
    writeback.io.ordinary.bits.data := 0x1234.U
    writeback.io.ordinary.bits.flags := 0.U
  }
}

class BackendBE2StorageSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import Instructions._
  "N03: eight II=1 MULs survive eight consecutive W2 conflicts and write once in order" in {
    simulate(new BE2W2StorageProbe) { d =>
      d.occupyW2.poke(false.B)
      val m = new Environment(d); m.reset()
      m.run(Seq(addi(20, 0, 3), addi(21, 0, 7)), 6)
      val start = m.cycle
      var pc = m.pc
      for (offset <- 0 until 24) {
        // Capture ordinary W2 for the complete E+4..E+11 result window.
        d.occupyW2.poke((offset >= 4 && offset < 12).B)
        val next = if (offset < 8) Some(pc -> mdu(offset + 1, 20, 21)) else None
        val accepted = m.step(next)
        if (offset < 8) { accepted mustBe true; pc += 4 }
      }
      d.occupyW2.poke(false.B)
      m.all("mulIn").takeRight(8) mustBe (start + 1 to start + 8)
      for (rd <- 1 to 8) {
        m.writes(rd).size mustBe 1
        m.writes(rd).head.data mustBe BigInt(21)
        m.written(rd) mustBe start + 12 + rd
      }
      d.io.observe.gprBusy.expect(0.U)
    }
  }
}

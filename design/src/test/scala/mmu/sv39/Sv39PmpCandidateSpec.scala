package flow.mmu.sv39

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import Sv39TestSupport._

class Sv39PmpCandidateSpec extends AnyFreeSpec with ChiselSim {
  "SOC3d candidate PA matches all leaf sizes while page faults retain zero public PA" in {
    simulate(new Sv39Mmu(withPmpCandidate = true)) { d =>
      val h = new Driver(d, latency = 3, seed = 61)
      h.backpressure = true
      val va = BigInt("40001278", 16)
      for (level <- 0 to 2) {
        h.fence(); h.memory.clear()
        val ppn = BigInt("80000", 16)
        if (level == 0) h.map4k(va, ppn, Rwx)
        else if (level == 1) {
          h.memory(pteAddress(Root, va, 2)) = pte(Middle, 1)
          h.memory(pteAddress(Middle, va, 1)) = pte(ppn, Rwx)
        } else h.memory(pteAddress(Root, va, 2)) = pte(ppn, Rwx)
        val pa = (ppn << 12) | (va & ((BigInt(1) << (12 + level * 9)) - 1))
        for (cmd <- 0 to 2) {
          assert(h.access(va, cmd) == Result("hit", pa))
          val p = h.setRequest(va, cmd)
          h.tick(); p.req.valid.poke(false.B)
          p.candidatePaddr.get.expect(pa.U); p.resp.bits.paddr.expect(pa.U)
          p.resp.bits.hit.expect(true.B); h.tick()
        }
        // MXR/SUM/privilege fault must never turn the unqualified candidate
        // into a public hit, even though the PPN is still present in the TLB.
        d.io.csr.priv.poke(0.U)
        val p = h.setRequest(va, 1)
        h.tick(); p.req.valid.poke(false.B)
        p.candidatePaddr.get.expect(pa.U); p.resp.bits.paddr.expect(0.U)
        p.resp.bits.pageFault.expect(true.B); p.resp.bits.hit.expect(false.B)
        h.tick(); d.io.csr.priv.poke(1.U)
      }
      h.fence(); d.io.csr.sv39.poke(false.B)
      val high = (BigInt(1) << 63) | 0x7c
      h.setRequest(high, 1); h.tick(); d.io.dtlb.req.valid.poke(false.B)
      d.io.dtlb.candidatePaddr.get.expect(high.U); d.io.dtlb.resp.bits.paddr.expect(high.U)
      d.io.dtlb.kill.poke(true.B); d.io.dtlb.resp.valid.expect(false.B)
      h.tick(); d.io.dtlb.kill.poke(false.B)
    }
  }
}

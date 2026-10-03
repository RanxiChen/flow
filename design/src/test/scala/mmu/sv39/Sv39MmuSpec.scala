package flow.mmu.sv39

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import Sv39TestSupport._

class Sv39MmuSpec extends AnyFreeSpec with ChiselSim {
  "T1 Bare, M and MPRV bypass preserve all 64 address bits" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d); val va = BigInt("fedcba9876543210", 16)
      d.io.csr.sv39.poke(false.B)
      for (cmd <- 0 to 2) assert(h.request(va, cmd) == Result("hit", va))
      d.io.csr.sv39.poke(true.B); d.io.csr.priv.poke(3.U)
      for (cmd <- 0 to 2) assert(h.request(va, cmd) == Result("hit", va))
      d.io.csr.mprv.poke(true.B); d.io.csr.mpp.poke(1.U)
      assert(h.request(va, 0).kind == "hit")
      assert(h.request(va, 1).kind == "pageFault")
      d.io.csr.priv.poke(1.U); d.io.csr.mpp.poke(3.U)
      assert(h.request(va, 2).kind == "hit"); assert(h.reads.isEmpty)
    }
  }
  "T2 noncanonical positive and negative addresses fault without a walk" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d)
      for (va <- Seq(BigInt(1) << 38, BigInt(1) << 39, BigInt("ffffff8000000000", 16)); cmd <- 0 to 2)
        assert(h.request(va, cmd).kind == "pageFault")
      assert(h.reads.isEmpty)
    }
  }
  for (latency <- Seq(1, 5)) {
    s"T3-T5 latency $latency: exact three-level timing and both walk-cache shortcuts" in {
      simulate(new Sv39Mmu()) { d =>
        val h = new Driver(d, latency); val va = BigInt(0x4123)
        h.map4k(va, 0xabc)
        val start = h.cycle
        assert(h.request(va).kind == "miss"); d.io.dtlb.req.ready.expect(false.B)
        h.waitReady()
        assert(h.reads.map(_._1).toSeq == Seq(start + 4, start + 4 + latency + 2, start + 4 + 2 * (latency + 2)))
        assert(h.cycle == start + 6 + latency + 2 * (latency + 2))
        assert(h.request(va) == Result("hit", BigInt(0xabc123)))
        val va2 = va + 0x1000; h.map4k(va2, 0xdef)
        val before = h.reads.size; val second = h.cycle
        assert(h.request(va2).kind == "miss"); h.waitReady()
        assert(h.reads.size - before == 1); assert(h.cycle == second + 6 + latency)
        assert(h.request(va2) == Result("hit", BigInt(0xdef123)))
        val va3 = va + 0x200000; h.map4k(va3, 0x456, bottom = Bottom + 1)
        val third = h.cycle; val count = h.reads.size
        assert(h.request(va3).kind == "miss"); h.waitReady()
        assert(h.reads.size - count == 2); assert(h.cycle == third + 6 + latency + latency + 2)
        assert(h.request(va3) == Result("hit", BigInt(0x456123)))
      }
    }
  }
  "T6 2 MiB and 1 GiB leaves translate every offset directly, including negative canonical VA" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d)
      val va2m = BigInt(0x201234); val ppn2m = BigInt(0x80000)
      h.memory(pteAddress(Root, va2m, 2)) = pte(Middle, 1)
      h.memory(pteAddress(Middle, va2m, 1)) = pte(ppn2m, Rwx)
      assert(h.access(va2m) == Result("hit", (ppn2m << 12) + 0x1234))
      val count = h.reads.size
      assert(h.request(0x3ffabc) == Result("hit", (ppn2m << 12) + 0x1ffabc))
      assert(h.reads.size == count)
      val va1g = BigInt("ffffffffc0004321", 16); val ppn1g = BigInt(0x100000)
      h.memory(pteAddress(Root, va1g, 2)) = pte(ppn1g, Rwx)
      assert(h.access(va1g, 0) == Result("hit", (ppn1g << 12) + 0x4321))
      val old = h.reads.size
      assert(h.request(BigInt("ffffffffffffabcd", 16), 0) == Result("hit", (ppn1g << 12) + 0x3fffabcd))
      assert(h.reads.size == old)
    }
  }
  "T7 malformed PTEs fault, do not refill or cache faults, and nonleaf U/A/D are ignored" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d); val va = BigInt(0x1234)
      val cases = Seq((2, pte(0x40001)), (1, pte(0x201)), (2, pte(0x40000, 0)),
        (2, pte(0x40000, 5)), (2, pte(0x40000) | (BigInt(1) << 54)),
        (2, pte(0x40000) | (BigInt(1) << 63)), (0, pte(0x500, 1)))
      for ((level, bad) <- cases) {
        h.fence(); h.map4k(va, 0x400)
        val base = Seq(Bottom, Middle, Root)(level)
        h.memory(pteAddress(base, va, level)) = bad
        assert(h.access(va).kind == "pageFault")
        // Delivered fault is a one-shot retry result; the next request must miss again.
        assert(h.request(va).kind == "miss"); h.waitReady()
        assert(h.request(va).kind == "pageFault")
      }
      h.fence(); h.map4k(va, 0x400)
      h.memory(pteAddress(Root, va, 2)) = pte(Middle, 0xd1)
      h.memory(pteAddress(Middle, va, 1)) = pte(Bottom, 0xd1)
      assert(h.access(va).kind == "hit")
    }
  }
  "T8 access faults at each walk level take precedence over invalid PTE data" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d, 5); val va = BigInt(0x1234)
      for (level <- 0 to 2; cmd <- 0 to 2) {
        h.fence(); h.faults.clear(); h.map4k(va, 0x400, Rwx)
        val addr = pteAddress(Seq(Bottom, Middle, Root)(level), va, level)
        h.memory(addr) = 0; h.faults += addr
        assert(h.access(va, cmd).kind == "accessFault")
        d.io.idle.expect(true.B)
      }
    }
  }
  "T9 exhaustive dynamic permission matrix for valid leaf PTEs" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d); val va = BigInt(0x1234)
      // All 6-bit R/W/X/U/A/D combinations that are structurally valid leaves.
      for (mask <- 0 until 64) {
        val flags = 1 | ((mask & 15) << 1) | ((mask & 48) << 2)
        val r = (flags & 2) != 0; val w = (flags & 4) != 0; val x = (flags & 8) != 0
        if ((r || x) && (r || !w)) {
          d.io.csr.priv.poke(1.U); d.io.csr.mprv.poke(false.B)
          h.fence(); h.map4k(va, 0xabc, flags)
          h.access(va, 0); h.access(va, 1)
          val reads = h.reads.size
          for ((priv, mprv, mpp) <- Seq((0, false, 0), (1, false, 1), (3, true, 0), (3, true, 1), (3, true, 3));
            cmd <- 0 to 2; sum <- Seq(false, true); mxr <- Seq(false, true)) {
            d.io.csr.priv.poke(priv.U); d.io.csr.mprv.poke(mprv.B); d.io.csr.mpp.poke(mpp.U)
            d.io.csr.sum.poke(sum.B); d.io.csr.mxr.poke(mxr.B)
            val result = h.request(va, cmd)
            val allowed = permission(flags, cmd, priv, mprv, mpp, sum, mxr)
            assert(result.kind == (if (allowed) "hit" else "pageFault"),
              s"flags=$flags cmd=$cmd priv=$priv mprv=$mprv mpp=$mpp sum=$sum mxr=$mxr")
          }
          assert(h.reads.size == reads, "permissions must be checked from current CSR without another walk")
        }
      }
    }
  }
  "T10 ASID isolation, global leaf sharing, and nonleaf G is not inherited" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d); val va = BigInt(0x1234)
      h.map4k(va, 0x400)
      h.memory(pteAddress(Root, va, 2)) = pte(Middle, 0x21)
      assert(h.access(va).pa == 0x400234)
      d.io.csr.asid.poke(0xffff.U); d.io.csr.rootPpn.poke((Root + 1).U)
      h.map4k(va, 0x500, root = Root + 1, middle = Middle + 1, bottom = Bottom + 1)
      assert(h.request(va).kind == "miss"); h.waitReady(); assert(h.request(va).pa == 0x500234)
      d.io.csr.asid.poke(0.U); d.io.csr.rootPpn.poke(Root.U)
      assert(h.request(va).pa == 0x400234)
      val global = va + 0x1000; h.map4k(global, 0x600, Read | 0x20)
      assert(h.access(global).kind == "hit")
      d.io.csr.asid.poke(0xffff.U)
      val before = h.reads.size; assert(h.request(global).pa == 0x600234); assert(h.reads.size == before)
    }
  }
  "T11 all sfence forms, ASID filtering, global preservation, superpage ranges, and walk-cache flush" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d); val va = BigInt(0x1234); val other = va + 0x1000
      h.map4k(va, 0x400); h.map4k(other, 0x500, Read | 0x20)
      h.access(va); h.access(other)
      d.io.csr.asid.poke(7.U); h.map4k(va, 0x600, root = Root + 1, middle = Middle + 1, bottom = Bottom + 1)
      d.io.csr.rootPpn.poke((Root + 1).U); h.access(va)
      h.fence(va, true, true, 0)
      assert(h.request(va).pa == 0x600234); assert(h.request(other).pa == 0x500234)
      d.io.csr.asid.poke(0.U); d.io.csr.rootPpn.poke(Root.U)
      val before = h.reads.size; assert(h.access(va).pa == 0x400234); assert(h.reads.size - before == 3)
      h.fence(other, true, true, 0); assert(h.request(other).kind == "hit")
      h.fence(va, true, false); assert(h.request(va).kind == "miss"); h.waitReady(); h.request(va)
      d.io.csr.asid.poke(7.U); d.io.csr.rootPpn.poke((Root + 1).U)
      assert(h.request(va).kind == "miss"); h.waitReady(); h.request(va)
      d.io.csr.asid.poke(0.U); d.io.csr.rootPpn.poke(Root.U)
      h.fence(rs2Nz = true, asid = 99); assert(h.request(other).kind == "miss"); h.waitReady(); h.request(other)
      // Selective invalidation uses the superpage range and ignores VA canonicality.
      h.fence(); val superVa = BigInt(0x40000000)
      h.memory(pteAddress(Root, superVa, 2)) = pte(0x100000, Read | 0x20)
      h.access(superVa); h.fence(superVa + 0x12345, true, true, 0)
      assert(h.request(superVa).kind == "hit")
      h.fence((BigInt(1) << 39) | (superVa + 0x12345), true, false)
      assert(h.request(superVa).kind == "miss"); h.waitReady(); h.request(superVa)
      h.fence(); assert(h.request(superVa).kind == "miss"); h.waitReady(); h.request(superVa)
    }
  }
  "T12 simultaneous misses service D before I" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d); val iv = BigInt(0x40001000); val dv = BigInt(0x1234)
      h.map4k(dv, 0x400); h.map4k(iv, 0x800, Rwx, middle = Middle + 1, bottom = Bottom + 1)
      h.setRequest(iv, 0); h.setRequest(dv, 1); h.tick()
      d.io.itlb.req.valid.poke(false.B); d.io.dtlb.req.valid.poke(false.B)
      assert(h.result(d.io.itlb).kind == "miss"); assert(h.result(d.io.dtlb).kind == "miss")
      h.tick(); h.waitReady(1); h.waitReady(0)
      assert(h.reads.head._2 == pteAddress(Root, dv, 2))
      assert(h.request(dv).pa == 0x400234); assert(h.request(iv, 0).pa == 0x800000)
    }
  }
  "T13 kills before, with and after grant; killed walks refill but never deliver faults" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d, 5); val iv = BigInt(0x40001000); val dv = BigInt(0x1234)
      h.map4k(dv, 0x400); h.map4k(iv, 0x800, Rwx, middle = Middle + 1, bottom = Bottom + 1)
      h.setRequest(iv, 0); h.setRequest(dv, 1); h.tick()
      d.io.itlb.req.valid.poke(false.B); d.io.dtlb.req.valid.poke(false.B); h.tick()
      // D wins grant, so I kill is strictly before its grant.
      d.io.itlb.kill.poke(true.B); h.tick(); d.io.itlb.kill.poke(false.B)
      d.io.itlb.req.ready.expect(true.B); h.waitReady(); assert(h.reads.size == 3)
      assert(h.request(iv, 0).kind == "miss")
      // Same-cycle grant+kill retains the miss until completion.
      d.io.itlb.kill.poke(true.B); h.tick(); d.io.itlb.kill.poke(false.B)
      d.io.itlb.req.ready.expect(false.B); h.waitReady(0)
      assert(h.request(iv, 0).kind == "hit")
      for (afterGrant <- Seq(false, true)) {
        h.fence(); val bad = BigInt(0x80001000)
        assert(h.request(bad).kind == "miss")
        if (afterGrant) h.tick(2)
        d.io.dtlb.kill.poke(true.B); h.tick(); d.io.dtlb.kill.poke(false.B)
        h.waitReady(); d.io.idle.expect(true.B)
        assert(h.request(bad).kind == "miss"); h.waitReady(); assert(h.request(bad).kind == "pageFault")
      }
      // kill suppresses an already pending fault and the current S1 response.
      h.fence(); assert(h.request(BigInt(0xc0001000)).kind == "miss"); h.waitReady()
      d.io.idle.expect(false.B); d.io.dtlb.kill.poke(true.B); h.tick(); d.io.dtlb.kill.poke(false.B)
      d.io.idle.expect(true.B)
      h.setRequest(dv, 1); h.tick(); d.io.dtlb.req.valid.poke(false.B)
      d.io.dtlb.kill.poke(true.B); d.io.dtlb.resp.valid.expect(false.B); h.tick()
      d.io.dtlb.kill.poke(false.B); h.tick(); d.io.idle.expect(true.B)
    }
  }
  "T14 back-to-back hit then miss drops the simultaneously accepted younger request" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d); val va = BigInt(0x1234); val missing = va + 0x1000
      h.map4k(va, 0x400); h.map4k(missing, 0x500); h.access(va)
      h.setRequest(va, 1); h.tick(); assert(h.result(d.io.dtlb).kind == "hit")
      h.setRequest(missing, 1); h.tick(); assert(h.result(d.io.dtlb).kind == "miss")
      h.setRequest(va, 1); d.io.dtlb.req.ready.expect(true.B); h.tick()
      d.io.dtlb.req.valid.poke(false.B); d.io.dtlb.resp.valid.expect(false.B)
      d.io.dtlb.req.ready.expect(false.B); h.waitReady()
      assert(h.request(missing).kind == "hit"); assert(h.request(va).kind == "hit")
    }
  }
  "T15 invalid ways are used first and tree PLRU chooses the next base and superpage victims" in {
    simulate(new Sv39Mmu(Sv39MmuParams(dtlb = Sv39TlbParams(1, 4, 4)))) { d =>
      val h = new Driver(d)
      val pages = (0 until 5).map(i => BigInt(0x1000 + i * 0x1000))
      for ((va, i) <- pages.zipWithIndex) h.map4k(va, 0x400 + i)
      for (va <- pages.take(4)) assert(h.access(va).kind == "hit")
      // Fill 0,1,2,3 then touch 0: the reference tree victim is way 2.
      h.request(pages(0)); h.access(pages(4))
      for (i <- Seq(0, 1, 3, 4)) assert(h.request(pages(i)).kind == "hit")
      assert(h.request(pages(2)).kind == "miss"); h.waitReady(); h.request(pages(2))
      h.fence()
      val large = (0 until 5).map(i => BigInt(i) << 30)
      for ((va, i) <- large.zipWithIndex) h.memory(pteAddress(Root, va, 2)) = pte(BigInt(i + 8) << 18)
      for (va <- large.take(4)) assert(h.access(va).kind == "hit")
      h.request(large(0)); h.access(large(4))
      for (i <- Seq(0, 1, 3, 4)) assert(h.request(large(i)).kind == "hit")
      assert(h.request(large(2)).kind == "miss"); h.waitReady(); h.request(large(2))
    }
  }
  "T16 random PTW backpressure preserves address and results with latency 1 and 5" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d, seed = 0x1639); h.backpressure = true
      for (l <- Seq(1, 5); i <- 0 until 16) {
        h.latency = l; val va = BigInt(0x1234 + i * 0x200000)
        h.map4k(va, 0x400 + i, bottom = Bottom + i)
        assert(h.access(va) == Result("hit", (BigInt(0x400 + i) << 12) | 0x234))
      }
    }
  }
  "T17 seeded random sparse page tables and accesses agree with the independent Sv39 walker" in {
    simulate(new Sv39Mmu()) { d =>
      val h = new Driver(d, 5, 0x1739); h.backpressure = true
      val pages = (0 until 48).map(i => BigInt(i) << 21)
      for ((va, i) <- pages.zipWithIndex) {
        val flags = Seq(Read, Rwx, 0x59, 0x53, 0x0f, 0x47, 0xc7)(h.rng.nextInt(7))
        h.map4k(va, 0x1000 + i, flags, bottom = Bottom + i)
        if (i % 9 == 0) h.memory(pteAddress(Middle, va, 1)) = pte(BigInt(i + 8) << 9, flags)
        if (i % 13 == 0) h.memory(pteAddress(Bottom + i, va, 0)) |= BigInt(1) << 54
        if (i % 17 == 0) h.faults += pteAddress(Bottom + i, va, 0)
      }
      for (iteration <- 0 until 400) {
        if (iteration % 37 == 0) h.fence()
        val va = pages(h.rng.nextInt(pages.size)) + h.rng.nextInt(4096)
        val cmd = h.rng.nextInt(3); val priv = h.rng.nextInt(2)
        val sum = h.rng.nextBoolean(); val mxr = h.rng.nextBoolean()
        d.io.csr.priv.poke(priv.U); d.io.csr.sum.poke(sum.B); d.io.csr.mxr.poke(mxr.B)
        val expected = reference(h.memory, h.faults, va, Root, cmd, priv, sum = sum, mxr = mxr)
        val actual = h.access(va, cmd)
        assert(actual.kind == expected.kind, s"iteration=$iteration va=$va cmd=$cmd expected=$expected actual=$actual")
        if (expected.kind == "hit") assert(actual.pa == expected.pa)
      }
    }
  }
}

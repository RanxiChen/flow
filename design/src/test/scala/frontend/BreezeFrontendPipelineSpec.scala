package flow.frontend

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeMemGeometry
import flow.interface._
import flow.mmu.BreezePmpDecode
import flow.mmu.sv39._
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable

class FetchPmpHarness extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool()); val va = Input(UInt(64.W)); val pa = Input(UInt(64.W))
    val cfg = Input(Vec(8, UInt(8.W))); val addr = Input(Vec(8, UInt(54.W)))
    val priv = Input(UInt(2.W)); val allowed = Output(Vec(2, Bool()))
  })
  val context = Wire(new BreezeMmuContext(64))
  context := 0.U.asTypeOf(context)
  context.privilege := io.priv; context.pmpRangesValid := true.B
  for (n <- 0 until 8) {
    context.pmpcfg(n) := io.cfg(n); context.pmpaddr(n) := io.addr(n)
    context.pmpRanges(n) := BreezePmpDecode.range(io.cfg(n), io.addr(n), if (n == 0) 0.U else io.addr(n - 1))
  }
  val pmp = Module(new BreezeFetchPmp(2))
  pmp.io.fire := io.fire; pmp.io.vaddr := io.va; pmp.io.context := context
  pmp.io.paddrs := VecInit(Seq(io.pa, io.pa ^ (1L << 30).U))
  pmp.io.select := 1.U; io.allowed := pmp.io.allowed
}

class BreezeFrontendPipelineSpec extends AnyFreeSpec with ChiselSim {
  private val geometry = BreezeMemGeometry(nCores = 1)
  private val params = BreezeFrontendPipelineParams()
  private val base = BigInt("80000000", 16)
  private case class Seen(pc: BigInt, raw: BigInt, instruction: BigInt, length: Int,
      pageFault: Boolean, accessFault: Boolean, secondFault: Boolean)
  private class Driver(d: BreezeFrontendMmuHarness, start: BigInt, seed: Int = 73) {
    val memory = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
    val ptes = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
    val random = new scala.util.Random(seed)
    var cycle = 0
    var refill: Option[(Int, BigInt)] = None
    var ptw: Option[(Int, BigInt)] = None
    var refills = Vector.empty[BigInt]
    var ptwReads = 0
    var f0Run = 0; var longestF0Run = 0
    var refillError = false
    d.io.resetPc.poke(start.U)
    d.io.context.poke(0.U.asTypeOf(new BreezeMmuContext(64)))
    d.io.context.privilege.poke(3.U); d.io.context.pmpRangesValid.poke(true.B)
    d.io.block.poke(false.B); d.io.redirect.valid.poke(false.B); d.io.redirect.bits.poke(start.U)
    d.io.fenceI.poke(false.B); d.io.sfence.poke(0.U.asTypeOf(d.io.sfence))
    d.io.resolve.valid.poke(false.B); d.io.resolve.bits.poke(0.U.asTypeOf(d.io.resolve.bits))
    d.io.committedBranch.valid.poke(false.B); d.io.committedBranch.bits.poke(false.B)
    d.io.instruction.ready.poke(true.B)
    d.io.refillRsp.vld.poke(false.B); d.io.refillRsp.data.poke(0.U); d.io.refillRsp.error.poke(false.B)
    d.io.ptwMem.req.ready.poke(true.B); d.io.ptwMem.resp.valid.poke(false.B)
    d.io.ptwMem.resp.bits.poke(0.U.asTypeOf(d.io.ptwMem.resp.bits))
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
    def put16(address: BigInt, value: Int): Unit = {
      memory(address) = value & 255; memory(address + 1) = (value >> 8) & 255
    }
    def put32(address: BigInt, value: BigInt): Unit = {
      for (i <- 0 until 4) memory(address + i) = ((value >> (i * 8)) & 255).toInt
    }
    def executableTor(end: BigInt): Unit = {
      d.io.context.privilege.poke(1.U)
      d.io.context.pmpcfg(0).poke(0x0c.U)
      d.io.context.pmpaddr(0).poke((end >> 2).U)
      d.io.context.pmpRanges(0).lower.poke(0.U)
      d.io.context.pmpRanges(0).upper.poke(end.U)
      d.io.context.pmpRanges(0).nonempty.poke(true.B)
      d.io.context.pmpRanges(0).lowerBlockPrevious.poke(((BigInt(1) << 58) - 1).U)
      d.io.context.pmpRanges(0).upperBlockPrevious.poke(((end >> 7) - 1).U)
    }
    def mapPage(va: BigInt, pa: BigInt): Unit = {
      ptes(0x1000 + ((va >> 30) & 511) * 8) = (BigInt(2) << 10) | 1
      ptes(0x2000 + ((va >> 21) & 511) * 8) = (BigInt(3) << 10) | 1
      ptes(0x3000 + ((va >> 12) & 511) * 8) = ((pa >> 12) << 10) | 0x4b
    }
    def tick(): Option[Seen] = {
      val returned = refill.filter(_._1 <= cycle)
      d.io.refillRsp.vld.poke(returned.nonEmpty.B)
      returned.foreach { case (_, address) =>
        val bits = (0 until 32).foldLeft(BigInt(0))((v, i) => v | (BigInt(memory(address + i)) << (i * 8)))
        d.io.refillRsp.data.poke(bits.U); d.io.refillRsp.error.poke(refillError.B)
      }
      val walk = ptw.filter(_._1 <= cycle)
      d.io.ptwMem.resp.valid.poke(walk.nonEmpty.B)
      walk.foreach { case (_, address) =>
        d.io.ptwMem.resp.bits.data.poke(ptes(address).U)
        d.io.ptwMem.resp.bits.accessFault.poke(false.B)
      }
      val seen = if (d.io.instruction.valid.peek().litToBoolean && d.io.instruction.ready.peek().litToBoolean) {
        val b = d.io.instruction.bits
        Some(Seen(b.pc.peek().litValue, b.raw.peek().litValue, b.instruction.peek().litValue,
          b.length.peek().litValue.toInt, b.pageFault.peek().litToBoolean,
          b.accessFault.peek().litToBoolean, b.faultSecondParcel.peek().litToBoolean))
      } else None
      if (returned.nonEmpty) refill = None
      if (walk.nonEmpty) ptw = None
      if (d.io.refillReq.req.peek().litToBoolean) {
        assert(refill.isEmpty, "multiple refill owners")
        val a = d.io.refillReq.paddr.peek().litValue
        refills :+= a; refill = Some((cycle + 3 + random.nextInt(5), a))
      }
      if (d.io.ptwMem.req.valid.peek().litToBoolean && d.io.ptwMem.req.ready.peek().litToBoolean) {
        assert(ptw.isEmpty, "multiple PTW reads")
        ptwReads += 1; ptw = Some((cycle + 2 + random.nextInt(4), d.io.ptwMem.req.bits.paddr.peek().litValue))
      }
      if (d.io.debug.f0Fire.peek().litToBoolean) f0Run += 1 else f0Run = 0
      longestF0Run = longestF0Run.max(f0Run)
      d.clock.step(); cycle += 1
      seen
    }
    def collect(n: Int, randomStalls: Boolean = false): Vector[Seen] = {
      val result = mutable.ArrayBuffer.empty[Seen]
      var limit = 2500
      while (result.size < n && limit > 0) {
        d.io.instruction.ready.poke((!randomStalls || random.nextInt(4) != 0).B)
        tick().foreach(result += _); limit -= 1
      }
      assert(result.size == n, s"frontend stopped at ${result.size}/$n after $cycle cycles")
      result.toVector
    }
  }

  "VIPT streams mixed RVC/RV32 through line boundaries and random consumer stalls" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base + 2)
      var address = base + 2
      val expected = (0 until 120).map { i =>
        val compressed = i % 3 != 1
        val raw = if (compressed) BigInt(1) else (BigInt(i & 2047) << 20) | 0x193
        if (compressed) h.put16(address, 1) else h.put32(address, raw)
        val e = Seen(address, raw, if (compressed) BigInt(0x13) else raw, if (compressed) 2 else 4, false, false, false)
        address += e.length; e
      }.toVector
      assert(h.collect(120, randomStalls = true) == expected)
      assert(h.longestF0Run >= 4, s"hit stream was serialized: longest run ${h.longestF0Run}")
    }
  }

  "cross-page RV32 reassembles after two real Sv39 walks" in {
    val va = BigInt("40000ffe", 16)
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, va)
      h.executableTor(base + 0x2000)
      d.io.context.satp.poke(((BigInt(8) << 60) | 1).U)
      h.mapPage(va, base); h.mapPage(va + 2, base + 0x1000)
      h.put32(base + 0xffe, BigInt("12300193", 16))
      h.put16(base + 0x1002, 1)
      val got = h.collect(2)
      assert(got(0) == Seen(va, BigInt("12300193", 16), BigInt("12300193", 16), 4, false, false, false))
      assert(got(1).pc == va + 4 && got(1).instruction == 0x13)
      assert(h.ptwReads >= 4, "both pages must be translated")
    }
  }

  "PMP allows a final RVC but faults the second parcel of a straddling RV32" in {
    for (compressed <- Seq(true, false)) {
      simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
        val h = new Driver(d, base + 2)
        h.executableTor(base + 4)
        if (compressed) h.put16(base + 2, 1) else h.put32(base + 2, BigInt("12300193", 16))
        val got = h.collect(1).head
        assert(got.pc == base + 2)
        assert(got.accessFault == !compressed && !got.pageFault)
        assert(got.secondFault == !compressed)
        assert(got.length == (if (compressed) 2 else 4))
        assert(h.refills == Vector(base), "denied second parcel must not issue a new refill")
      }
    }
  }

  "redirect drains a killed refill and same-address reuse returns fresh data" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      h.put32(base, BigInt("11100193", 16)); h.put16(base + 4, 1)
      while (h.refills.isEmpty) h.tick()
      d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke(base.U)
      h.tick(); d.io.redirect.valid.poke(false.B)
      h.put32(base, BigInt("22200193", 16))
      val got = h.collect(1).head
      assert(got.instruction == BigInt("22200193", 16) && got.pc == base)
      assert(h.refills.take(2) == Vector(base, base))
    }
  }

  "F2 direct call and return use instruction length and speculative RAS" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      h.put32(base, BigInt("040000ef", 16)) // jal ra,+64
      h.put16(base + 4, 1); h.put16(base + 6, 1)
      h.put32(base + 64, BigInt("00008067", 16)) // ret
      val got = h.collect(4)
      assert(got.map(_.pc) == Vector(base, base + 64, base + 4, base + 6))
      d.io.debug.rasCount.expect(0.U)
    }
  }

  "F2 repairs missing NT branch history and global cancellation restores committed history" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      h.put32(base, BigInt("00001463", 16)); h.put16(base + 4, 1)
      d.io.committedBranch.valid.poke(true.B); d.io.committedBranch.bits.poke(true.B)
      d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke(base.U)
      h.tick(); d.io.committedBranch.valid.poke(false.B); d.io.redirect.valid.poke(false.B)
      d.io.debug.specHistory.expect(1.U)
      val got = h.collect(2)
      assert(got.map(_.pc) == Vector(base, base + 4))
      d.io.debug.specHistory.expect(2.U)
      d.io.redirect.valid.poke(true.B); h.tick(); d.io.redirect.valid.poke(false.B)
      d.io.debug.specHistory.expect(1.U)
    }
  }

  "split PMP matches integer first-overlap rules on page and region boundaries" in {
    simulate(new FetchPmpHarness) { d =>
      d.io.fire.poke(false.B); d.io.va.poke(0.U); d.io.pa.poke(0.U); d.io.priv.poke(1.U)
      d.io.cfg.foreach(_.poke(0.U)); d.io.addr.foreach(_.poke(0.U))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val cfg = Vector(0x0c, 0x1c, 0x94, 0, 0, 0, 0, 0)
      val addr = Vector(BigInt(0x1010 >> 2), BigInt((0x2000 >> 2) | 3), BigInt(0x3000 >> 2), BigInt(0), BigInt(0), BigInt(0), BigInt(0), BigInt(0))
      cfg.indices.foreach(i => { d.io.cfg(i).poke(cfg(i).U); d.io.addr(i).poke(addr(i).U) })
      def reference(pa: BigInt, priv: Int): Boolean = {
        val ranges = cfg.indices.flatMap { i =>
          val mode = (cfg(i) >> 3) & 3
          if (mode == 0) None else {
            val boundary = addr(i) << 2
            val mask = if (mode == 3) ((addr(i) ^ (addr(i) + 1)) << 2) | 3 else BigInt(3)
            val lower = if (mode == 1) (if (i == 0) BigInt(0) else addr(i - 1) << 2) else boundary & ~mask
            val upper = if (mode == 1) boundary else lower + mask + 1
            Some((i, lower, upper))
          }
        }
        ranges.find { case (_, lo, hi) => hi > lo && pa < hi && pa + 1 >= lo } match {
          case None => priv == 3
          case Some((i, lo, hi)) => pa >= lo && pa + 1 < hi && ((priv == 3 && (cfg(i) & 128) == 0) || (cfg(i) & 4) != 0)
        }
      }
      for (priv <- Seq(1, 3); pa <- Seq(0x100c, 0x100e, 0x1010, 0x1ffc, 0x2000, 0x201c, 0x201e, 0x2020, 0x2ffc, 0x3000, 0x3002, 0x3004)) {
        d.io.priv.poke(priv.U); d.io.va.poke((pa & 4095).U); d.io.pa.poke(pa.U)
        d.io.fire.poke(true.B); d.clock.step(); d.io.fire.poke(false.B)
        d.io.allowed(0).expect(reference(pa, priv).B)
        d.io.allowed(1).expect(reference(pa + 2, priv).B)
      }
    }
  }

  "warm RV32 hits accept a new fetch window every cycle" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      for (i <- 0 until 100) h.put32(base + i * 4, 0x13)
      h.collect(64)
      d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke(base.U)
      h.tick(); d.io.redirect.valid.poke(false.B)
      h.f0Run = 0; h.longestF0Run = 0
      val got = h.collect(32)
      assert(got.map(_.pc) == (0 until 32).map(i => base + i * 4).toVector)
      assert(h.longestF0Run >= 16, s"warm hit II=1 lost: run=${h.longestF0Run}")
    }
  }

  "missing second page faults the original straddling instruction precisely" in {
    val va = BigInt("40000ffe", 16)
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, va)
      h.executableTor(base + 0x2000); d.io.context.satp.poke(((BigInt(8) << 60) | 1).U)
      h.mapPage(va, base); h.put16(base + 0xffe, 0x193)
      val got = h.collect(1).head
      assert(got.pc == va && got.length == 4 && got.pageFault && !got.accessFault && got.secondFault)
      assert(h.refills == Vector(base + 0xfe0))
    }
  }

  "resolution overrides younger decode and restores before-history plus actual direction" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      h.put32(base, BigInt("04000063", 16)); h.put16(base + 4, 1); h.put16(base + 64, 1)
      assert(h.collect(1).head.pc == base)
      d.io.instruction.ready.poke(false.B)
      d.io.resolve.bits.eventPc.poke((base + 2).U)
      d.io.resolve.bits.kind.poke(1.U); d.io.resolve.bits.taken.poke(true.B)
      d.io.resolve.bits.target.poke((base + 64).U); d.io.resolve.bits.nextPc.poke((base + 64).U)
      d.io.resolve.bits.historyBefore.poke(0.U)
      d.io.resolve.bits.phtIndex.poke(((base + 2) >> 1 & 1023).U)
      d.io.resolve.bits.incorrect.poke(true.B); d.io.resolve.valid.poke(true.B)
      d.io.resolve.ready.expect(true.B); h.tick(); d.io.resolve.valid.poke(false.B)
      d.io.debug.specHistory.expect(1.U)
      val got = h.collect(1).head
      assert(got.pc == base + 64 && !got.accessFault)
    }
  }

  "stale BTB at a RV32 head is invalidated without losing its second parcel" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      d.io.block.poke(true.B)
      d.io.resolve.bits.eventPc.poke(base.U); d.io.resolve.bits.kind.poke(2.U)
      d.io.resolve.bits.target.poke((base + 64).U); d.io.resolve.bits.nextPc.poke((base + 64).U)
      d.io.resolve.bits.taken.poke(true.B); d.io.resolve.valid.poke(true.B)
      d.io.resolve.ready.expect(true.B); h.tick(); d.io.resolve.valid.poke(false.B)
      for (_ <- 0 until 8) h.tick()
      h.put32(base, BigInt("12300193", 16)); h.put16(base + 4, 1); h.put16(base + 64, 1)
      d.io.block.poke(false.B)
      val got = h.collect(2)
      assert(got.map(_.pc) == Vector(base, base + 4))
      assert(got.head.instruction == BigInt("12300193", 16))
    }
  }

  "RAS recovery includes the resolving call before a new return uses it" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      h.put32(base, BigInt("040000ef", 16)); h.put16(base + 4, 1)
      h.put32(base + 64, BigInt("00008067", 16)); h.put32(base + 128, BigInt("00008067", 16))
      assert(h.collect(1).head.pc == base)
      d.io.instruction.ready.poke(false.B)
      d.io.resolve.bits.eventPc.poke((base + 2).U); d.io.resolve.bits.kind.poke(2.U)
      d.io.resolve.bits.taken.poke(true.B); d.io.resolve.bits.incorrect.poke(true.B)
      d.io.resolve.bits.target.poke((base + 128).U); d.io.resolve.bits.nextPc.poke((base + 128).U)
      d.io.resolve.bits.rasPush.poke(true.B); d.io.resolve.bits.returnAddress.poke((base + 4).U)
      d.io.resolve.valid.poke(true.B); d.io.resolve.ready.expect(true.B)
      h.tick(); d.io.resolve.valid.poke(false.B)
      d.io.debug.rasCount.expect(1.U)
      assert(h.collect(2).map(_.pc) == Vector(base + 128, base + 4))
    }
  }

  "sfence remapping preserves VIPT index but compares the new physical tag" in {
    val va = BigInt("40000000", 16)
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, va)
      h.executableTor(base + 0x3000); d.io.context.satp.poke(((BigInt(8) << 60) | 1).U)
      h.mapPage(va, base)
      for (i <- 0 until 32) h.put32(base + i * 4, 0x13)
      h.put32(base + 0x1000, BigInt("45600193", 16))
      h.collect(2); d.io.block.poke(true.B)
      for (_ <- 0 until 30) h.tick()
      d.io.idle.expect(true.B)
      val reads = h.ptwReads
      h.mapPage(va, base + 0x1000)
      d.io.sfence.valid.poke(true.B); h.tick(); d.io.sfence.valid.poke(false.B)
      d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke(va.U)
      h.tick(); d.io.redirect.valid.poke(false.B); d.io.block.poke(false.B)
      val got = h.collect(1).head
      assert(got.pc == va && got.instruction == BigInt("45600193", 16))
      assert(h.ptwReads > reads)
    }
  }

  "a high physical alias faults before using a low-address cached instruction" in {
    val va = BigInt("40000000", 16)
    val high = base + (BigInt(1) << geometry.paddrBits)
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, va)
      h.executableTor(high + 0x1000)
      d.io.context.satp.poke(((BigInt(8) << 60) | 1).U)
      h.mapPage(va, base)
      for (i <- 0 until 32) h.put32(base + i * 4, BigInt("12300193", 16))
      assert(h.collect(1).head.instruction == BigInt("12300193", 16))
      d.io.block.poke(true.B); for (_ <- 0 until 30) h.tick()
      d.io.idle.expect(true.B)
      val refills = h.refills.size
      h.mapPage(va, high)
      d.io.sfence.valid.poke(true.B); h.tick(); d.io.sfence.valid.poke(false.B)
      d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke(va.U); h.tick()
      d.io.redirect.valid.poke(false.B); d.io.block.poke(false.B)
      val got = h.collect(1).head
      assert(got.pc == va && got.accessFault && !got.pageFault)
      assert(got.raw == 0 && h.refills.size == refills,
        "unsupported high PA must neither execute an aliased hit nor allocate a refill")
    }
  }

  "2 MiB and 1 GiB leaves participate in parallel fetch PMP comparisons" in {
    val va = BigInt("40000002", 16)
    for (level <- Seq(1, 2)) {
      simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
        val h = new Driver(d, va)
        h.executableTor(base + 0x2000); d.io.context.satp.poke(((BigInt(8) << 60) | 1).U)
        val leaf = ((base >> 12) << 10) | 0x4b
        if (level == 2) h.ptes(0x1000 + ((va >> 30) & 511) * 8) = leaf
        else {
          h.ptes(0x1000 + ((va >> 30) & 511) * 8) = (BigInt(2) << 10) | 1
          h.ptes(0x2000 + ((va >> 21) & 511) * 8) = leaf
        }
        h.put16(base + 2, 1)
        val got = h.collect(1).head
        assert(got.pc == va && got.instruction == 0x13 && !got.accessFault && !got.pageFault)
      }
    }
  }

  "taken prediction on a missed group discards its deferred sequential request" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      d.io.block.poke(true.B)
      d.io.resolve.bits.eventPc.poke((base + 2).U); d.io.resolve.bits.kind.poke(2.U)
      d.io.resolve.bits.target.poke((base + 8).U); d.io.resolve.bits.nextPc.poke((base + 8).U)
      d.io.resolve.bits.taken.poke(true.B); d.io.resolve.valid.poke(true.B)
      h.tick(); d.io.resolve.valid.poke(false.B)
      for (_ <- 0 until 8) h.tick()
      h.put32(base, BigInt("0080006f", 16)); h.put16(base + 4, 1); h.put16(base + 8, 1)
      d.io.block.poke(false.B)
      assert(h.collect(2).map(_.pc) == Vector(base, base + 8))
    }
  }

  "instruction output remains stable while stalled and resumes in order" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      for (i <- 0 until 32) h.put16(base + i * 2, 1)
      d.io.instruction.ready.poke(false.B)
      var limit = 200
      while (!d.io.instruction.valid.peek().litToBoolean && limit > 0) { h.tick(); limit -= 1 }
      assert(limit > 0)
      for (_ <- 0 until 12) {
        d.io.instruction.valid.expect(true.B); d.io.instruction.bits.pc.expect(base.U)
        d.io.instruction.bits.raw.expect(1.U); d.io.instruction.bits.instruction.expect(0x13.U)
        h.tick()
      }
      assert(h.collect(2).map(_.pc) == Vector(base, base + 2))
    }
  }

  "permission CSR restart drops all previously authorized queued instructions" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      h.executableTor(base + 0x1000)
      for (i <- 0 until 32) h.put32(base + i * 4, 0x13)
      d.io.instruction.ready.poke(false.B)
      var limit = 200
      while (!d.io.instruction.valid.peek().litToBoolean && limit > 0) { h.tick(); limit -= 1 }
      assert(limit > 0)
      val refills = h.refills.size
      d.io.context.pmpcfg(0).poke(0x08.U) // TOR without X
      d.io.context.permissionEvent.poke(true.B)
      d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke((base + 16).U)
      h.tick(); d.io.redirect.valid.poke(false.B); d.io.context.permissionEvent.poke(false.B)
      val got = h.collect(1).head
      assert(got.pc == base + 16 && got.accessFault && !got.pageFault)
      assert(h.refills.size == refills)
    }
  }

  "refill errors return a precise fault and do not install an executable line" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      h.put32(base, BigInt("12300193", 16)); h.refillError = true
      val fault = h.collect(1).head
      assert(fault.pc == base && fault.accessFault && !fault.pageFault)
      h.refillError = false
      d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke(base.U)
      h.tick(); d.io.redirect.valid.poke(false.B)
      val got = h.collect(1).head
      assert(got.pc == base && got.instruction == BigInt("12300193", 16) && !got.accessFault)
      assert(h.refills.take(2) == Vector(base, base))
    }
  }

  "two RVC branches select NT-shifted history and discard the younger slot after T" in {
    for (firstTaken <- Seq(false, true)) {
      simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
        val h = new Driver(d, base)
        d.io.block.poke(true.B)
        def train(pc: BigInt, before: Int, taken: Boolean, target: BigInt): Unit = {
          d.io.resolve.bits.eventPc.poke(pc.U)
          d.io.resolve.bits.historyBefore.poke(before.U)
          d.io.resolve.bits.phtIndex.poke((((pc >> 1) & 1023) ^ before).U)
          d.io.resolve.bits.kind.poke(1.U); d.io.resolve.bits.taken.poke(taken.B)
          d.io.resolve.bits.target.poke(target.U); d.io.resolve.bits.nextPc.poke(target.U)
          d.io.resolve.valid.poke(true.B)
          d.io.resolve.ready.expect(true.B); h.tick(); d.io.resolve.valid.poke(false.B)
          for (_ <- 0 until 8) h.tick()
        }
        // Allocate the first BTB entry under H=0, then make B(index=0)
        // strongly NT. Under query H=1, A=1, B=0, C=3 are distinct banks.
        train(base, 0, true, base + 16)
        train(base, 0, false, base + 16)
        train(base, 0, false, base + 16)
        train(base + 2, 2, true, base + 66) // C predicts T, B predicts NT.
        if (firstTaken) train(base, 1, true, base + 16)
        h.put16(base, 0xc801) // c.beqz s0,+16
        h.put16(base + 2, 0xc021) // c.beqz s0,+64
        for (i <- Seq(4, 16, 18, 66, 68)) h.put16(base + i, 1)
        d.io.committedBranch.valid.poke(true.B); d.io.committedBranch.bits.poke(true.B)
        d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke(base.U)
        h.tick(); d.io.redirect.valid.poke(false.B); d.io.committedBranch.valid.poke(false.B)
        d.io.block.poke(false.B)
        val seen = mutable.ArrayBuffer.empty[(BigInt, BigInt, BigInt, Boolean)]
        var limit = 300
        while (seen.size < 3 && limit > 0) {
          if (d.io.instruction.valid.peek().litToBoolean) {
            val b = d.io.instruction.bits
            seen += ((b.pc.peek().litValue, b.historyBefore.peek().litValue,
              b.phtIndex.peek().litValue, b.predictedTaken.peek().litToBoolean))
          }
          h.tick(); limit -= 1
        }
        assert(seen.size == 3)
        assert(seen.head == ((base, BigInt(1), BigInt(1), firstTaken)))
        if (firstTaken) {
          assert(seen.map(_._1).toVector == Vector(base, base + 16, base + 18))
          d.io.debug.specHistory.expect(3.U)
        } else {
          assert(seen(1) == ((base + 2, BigInt(2), BigInt(3), true)))
          assert(seen(2)._1 == base + 66)
          d.io.debug.specHistory.expect(5.U)
        }
      }
    }
  }

  "compressed JALR, JR and J predecode alongside expansion and preserve return length" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      d.io.block.poke(true.B)
      d.io.resolve.bits.eventPc.poke(base.U); d.io.resolve.bits.kind.poke(3.U)
      d.io.resolve.bits.target.poke((base + 64).U); d.io.resolve.bits.nextPc.poke((base + 64).U)
      d.io.resolve.bits.taken.poke(true.B); d.io.resolve.valid.poke(true.B)
      d.io.resolve.ready.expect(true.B); h.tick(); d.io.resolve.valid.poke(false.B)
      for (_ <- 0 until 8) h.tick()
      h.put16(base, 0x9082) // c.jalr ra: same link register, push only
      h.put16(base + 2, 0xa081) // c.j +64
      h.put16(base + 64, 0x8082) // c.jr ra
      h.put16(base + 66, 1)
      d.io.block.poke(false.B)
      val got = h.collect(4)
      assert(got.map(_.pc) == Vector(base, base + 64, base + 2, base + 66))
      assert(got.map(_.instruction) == Vector(BigInt(0x000080e7), BigInt(0x00008067),
        BigInt(0x0400006f), BigInt(0x13)))
      assert(got.forall(_.length == 2))
      d.io.debug.rasCount.expect(0.U); d.io.debug.specHistory.expect(0.U)
    }
  }

  "registered timing-boundary FIFO preserves ordering under stalls and cancellation" in {
    simulate(new FrontendBoundaryQueue(UInt(16.W))) { d =>
      val random = new scala.util.Random(9821)
      val expected = mutable.Queue.empty[Int]
      d.io.enq.valid.poke(false.B); d.io.enq.bits.poke(0.U); d.io.deq.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      for (cycle <- 0 until 600) {
        val cancel = cycle == 37 || cycle == 183 || cycle == 311
        val valid = random.nextInt(4) != 0
        val ready = random.nextBoolean()
        d.io.enq.valid.poke(valid.B); d.io.enq.bits.poke(cycle.U)
        d.io.deq.ready.poke(ready.B); d.reset.poke(cancel.B)
        d.io.deq.valid.expect(expected.nonEmpty.B)
        if (expected.nonEmpty) d.io.deq.bits.expect(expected.front.U)
        val accepted = valid && d.io.enq.ready.peek().litToBoolean
        val delivered = ready && d.io.deq.valid.peek().litToBoolean
        if (delivered) expected.dequeue()
        if (accepted) expected.enqueue(cycle)
        assert(expected.size <= 2)
        d.clock.step()
        if (cancel) expected.clear()
      }
      d.reset.poke(false.B); d.io.enq.valid.poke(false.B); d.io.deq.ready.poke(true.B)
      while (expected.nonEmpty) {
        d.io.deq.valid.expect(true.B); d.io.deq.bits.expect(expected.dequeue().U); d.clock.step()
      }
      d.io.deq.valid.expect(false.B)
    }
  }

  "pipelined iTLB kill discards a sampled query without feeding kill into request ready" in {
    simulate(new Sv39Tlb(Sv39MmuParams().itlb, instruction = true, pipelinedFetchKill = true)) { d =>
      d.io.csr.poke(0.U.asTypeOf(new MmuCsrIO)); d.io.csr.priv.poke(3.U)
      d.io.sfence.poke(0.U.asTypeOf(new SfenceIO))
      d.io.grant.poke(false.B); d.io.done.valid.poke(false.B)
      d.io.done.bits.poke(0.U.asTypeOf(new PtwDone))
      d.io.port.req.valid.poke(false.B); d.io.port.req.bits.cmd.poke(MmuCmd.Fetch)
      d.io.port.req.bits.vaddr.poke(base.U); d.io.port.kill.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.port.req.valid.poke(true.B); d.io.port.kill.poke(true.B)
      d.io.port.req.ready.expect(true.B); d.clock.step()
      d.io.port.req.valid.poke(false.B); d.io.port.kill.poke(false.B)
      d.io.port.resp.valid.expect(false.B)
      d.io.port.req.valid.poke(true.B); d.io.port.req.bits.vaddr.poke((base + 4).U)
      d.clock.step(); d.io.port.req.valid.poke(false.B)
      d.io.port.resp.valid.expect(true.B); d.io.port.resp.bits.hit.expect(true.B)
      d.io.port.resp.bits.paddr.expect((base + 4).U)
    }
  }

  "refill acceptance handles same-edge kill, later cancellation and fence during installation" in {
    for (mode <- 0 until 3) {
      simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
        val h = new Driver(d, base)
        val old = BigInt("11100193", 16); val fresh = BigInt("22200193", 16)
        h.put32(base, old); h.put16(base + 4, 1)
        var limit = 100
        while (h.refill.isEmpty && limit > 0) { h.tick(); limit -= 1 }
        assert(limit > 0)
        h.refill = Some((h.cycle, base))
        d.io.redirect.valid.poke((mode == 0).B); d.io.redirect.bits.poke(base.U)
        h.tick() // Response acceptance; mode 0 cancels at exactly this edge.
        h.put32(base, fresh)
        d.io.redirect.valid.poke(true.B); d.io.fenceI.poke((mode == 2).B)
        h.tick() // A previously accepted fill writes here; fence must win valid.
        d.io.redirect.valid.poke(false.B); d.io.fenceI.poke(false.B)
        val got = h.collect(1).head
        assert(got.pc == base && got.instruction == (if (mode == 1) old else fresh))
        assert(h.refills.count(_ == base) == (if (mode == 1) 1 else 2))
      }
    }
  }

  "timing harness clock and registered interfaces deliver an ordered stream with stalls" in {
    simulate(new BreezeFrontendTimingHarness) { d =>
      d.io.resetPc.poke(base.U); d.io.context.poke(0.U.asTypeOf(new BreezeMmuContext(64)))
      d.io.context.privilege.poke(3.U); d.io.context.pmpRangesValid.poke(true.B)
      d.io.block.poke(false.B); d.io.redirect.valid.poke(false.B); d.io.redirect.bits.poke(base.U)
      d.io.fenceI.poke(false.B); d.io.sfence.poke(0.U.asTypeOf(new SfenceIO))
      d.io.resolve.valid.poke(false.B); d.io.resolve.bits.poke(0.U.asTypeOf(d.io.resolve.bits))
      d.io.committedBranch.valid.poke(false.B); d.io.committedBranch.bits.poke(false.B)
      d.io.instruction.ready.poke(false.B)
      d.io.refillRsp.vld.poke(false.B); d.io.refillRsp.data.poke(0.U); d.io.refillRsp.error.poke(false.B)
      d.io.ptwMem.req.ready.poke(true.B); d.io.ptwMem.resp.valid.poke(false.B)
      d.io.ptwMem.resp.bits.poke(0.U.asTypeOf(d.io.ptwMem.resp.bits))
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      val random = new scala.util.Random(508)
      val line = (0 until 8).foldLeft(BigInt(0))((x, i) => x | (BigInt(0x13) << (i * 32)))
      var due: Option[Int] = None
      var seen = 0; var cycle = 0
      while (seen < 24 && cycle < 500) {
        val returned = due.contains(cycle)
        d.io.refillRsp.vld.poke(returned.B); d.io.refillRsp.data.poke(line.U)
        if (returned) due = None
        val ready = random.nextInt(4) != 0
        d.io.instruction.ready.poke(ready.B)
        if (ready && d.io.instruction.valid.peek().litToBoolean) {
          d.io.instruction.bits.pc.expect((base + seen * 4).U)
          d.io.instruction.bits.instruction.expect(0x13.U)
          d.io.instruction.bits.length.expect(4.U)
          seen += 1
        }
        if (d.io.refillReq.req.peek().litToBoolean) {
          assert(due.isEmpty)
          assert((d.io.refillReq.paddr.peek().litValue & 31) == 0)
          due = Some(cycle + 3)
        }
        d.clock.step(); cycle += 1
      }
      assert(seen == 24, s"timing harness stalled at $seen instructions")
    }
  }

  "taken first-slot prefix preserves the unconsumed second-slot BTB entry" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      d.io.block.poke(true.B)
      for ((pc, target) <- Seq((base, base + 16), (base + 2, base + 66))) {
        d.io.resolve.bits.eventPc.poke(pc.U); d.io.resolve.bits.kind.poke(2.U)
        d.io.resolve.bits.target.poke(target.U); d.io.resolve.bits.nextPc.poke(target.U)
        d.io.resolve.bits.taken.poke(true.B); d.io.resolve.valid.poke(true.B)
        d.io.resolve.ready.expect(true.B); h.tick(); d.io.resolve.valid.poke(false.B)
        for (_ <- 0 until 8) h.tick()
      }
      h.put16(base, 0xa801) // c.j +16: slot 1 is unconsumed, not a false CFI.
      h.put16(base + 2, 0xa081) // c.j +64
      h.put16(base + 16, 1); h.put16(base + 18, 1); h.put16(base + 66, 1)
      d.io.block.poke(false.B)
      assert(h.collect(2).map(_.pc) == Vector(base, base + 16))
      d.io.block.poke(true.B)
      for (_ <- 0 until 30) h.tick()
      d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke((base + 2).U)
      h.tick(); d.io.redirect.valid.poke(false.B); d.io.block.poke(false.B)
      val seen = mutable.ArrayBuffer.empty[Seen]
      var early = false; var limit = 200
      while (seen.size < 2 && limit > 0) {
        early ||= d.io.debug.f1Redirect.peek().litToBoolean
        h.tick().foreach(seen += _); limit -= 1
      }
      assert(seen.map(_.pc).toVector == Vector(base + 2, base + 66))
      assert(early, "a valid younger BTB entry was invalidated by an older taken prefix")
    }
  }

  "sequential indirect misses apply both RAS hints and forward a first-slot push to a return" in {
    for (secondIsReturn <- Seq(true, false)) {
      simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
        val h = new Driver(d, base)
        h.put16(base, 0x9502) // c.jalr a0: no target entry, predict sequentially.
        h.put16(base + 2, if (secondIsReturn) 0x8082 else 0x9502)
        for (off <- 4 until 32 by 2) h.put16(base + off, 1)
        var firstCount: Option[Int] = None
        var secondPrediction: Option[(Boolean, BigInt)] = None
        val seen = mutable.ArrayBuffer.empty[Seen]
        var limit = 200
        while (seen.size < 2 && limit > 0) {
          val fire = d.io.debug.f2Fire.peek().litToBoolean
          if (d.io.instruction.valid.peek().litToBoolean &&
              d.io.instruction.bits.pc.peek().litValue == base + 2) {
            secondPrediction = Some((d.io.instruction.bits.predictedTaken.peek().litToBoolean,
              d.io.instruction.bits.predictedTarget.peek().litValue))
          }
          h.tick().foreach(seen += _)
          if (fire && firstCount.isEmpty) firstCount = Some(d.io.debug.rasCount.peek().litValue.toInt)
          limit -= 1
        }
        assert(seen.map(_.pc).toVector == Vector(base, base + 2))
        assert(firstCount.contains(if (secondIsReturn) 0 else 2))
        assert(secondPrediction.nonEmpty)
        if (secondIsReturn) assert(secondPrediction.contains((true, base + 2)))
        else assert(!secondPrediction.get._1)
      }
    }
  }

  "history survives a full two-window return queue and long consumer backpressure" in {
    simulate(new BreezeFrontendMmuHarness(geometry, params)) { d =>
      val h = new Driver(d, base)
      d.io.block.poke(true.B)
      for (off <- 0 until 128 by 2) h.put16(base + off, 1)
      h.put16(base + 20, 0xc801) // NT branch in the queued tail, not its head.
      d.io.resolve.bits.eventPc.poke((base + 20).U)
      d.io.resolve.bits.kind.poke(1.U); d.io.resolve.bits.taken.poke(true.B)
      d.io.resolve.bits.target.poke((base + 36).U)
      // Allocate the BTB while keeping the actual queried counters weak NT.
      d.io.resolve.bits.phtIndex.poke(1023.U)
      d.io.resolve.valid.poke(true.B); d.io.resolve.ready.expect(true.B)
      h.tick(); d.io.resolve.valid.poke(false.B)
      for (_ <- 0 until 12) h.tick()
      d.io.committedBranch.valid.poke(true.B); d.io.committedBranch.bits.poke(true.B)
      d.io.redirect.valid.poke(true.B); d.io.redirect.bits.poke(base.U)
      h.tick(); d.io.committedBranch.valid.poke(false.B); d.io.redirect.valid.poke(false.B)
      d.io.block.poke(false.B); d.io.instruction.ready.poke(false.B)
      for (_ <- 0 until 80) h.tick()
      d.io.debug.specHistory.expect(1.U)
      d.io.debug.f0Fire.expect(false.B); d.io.debug.f1Fire.expect(false.B)
      d.io.instruction.ready.poke(true.B)
      val histories = mutable.Map.empty[BigInt, BigInt]
      val seen = mutable.ArrayBuffer.empty[Seen]
      var limit = 300
      while (seen.size < 14 && limit > 0) {
        if (d.io.instruction.valid.peek().litToBoolean) {
          histories(d.io.instruction.bits.pc.peek().litValue) = d.io.instruction.bits.historyBefore.peek().litValue
        }
        h.tick().foreach(seen += _); limit -= 1
      }
      assert(seen.map(_.pc).toVector == (0 until 14).map(i => base + 2 * i).toVector)
      assert(histories(base + 20) == 1)
      assert(histories(base + 22) == 2)
      assert(histories(base + 24) == 2, "query after a stall forgot the queued tail's NT branch")
      d.io.debug.specHistory.expect(2.U)
    }
  }
}

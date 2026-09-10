package flow.cache

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.DefaultDCacheConfig
import flow.interface.{BreezeAmoFunc, BreezeMemOp}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Coherence-protocol and RV64A behaviors of the L1D: MESI states, probe
  * responses, the three transient races (upgrade race, Put cancel, SC
  * reservation kill), LR/SC and AMO. CPU-side geometry/MMIO/flush behaviors
  * live in BreezeDCacheSetAssocSpec; the harness is shared.
  */
class BreezeDCacheCoherentSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import DCacheTestOps._
  private val cfg = DefaultDCacheConfig()
  private val word = BigInt("ffffffffffffffff", 16)

  private def sramAddr(tagLsb: Int, set: Int, offset: Int = 0): BigInt =
    BigInt(0x11000000L) + (BigInt(tagLsb) << 11) + (BigInt(set) << 5) + offset
  private def lineBase(addr: BigInt): BigInt = addr & ~BigInt(31)
  private def newDut(hartId: Int = 0, hartIdWidth: Int = 1) =
    new BreezeDCache(cfg, hartId = hartId, hartIdWidth = hartIdWidth)

  // ===== MESI stable states =====

  "GetS grants E; a store on the E line silently upgrades to M without GetM" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(0, 3)
      h.load(addr)._4.map(_.opcode) mustBe Seq(GetS)
      val value = BigInt("0123456789abcdef", 16)
      // Silent E->M: no coherence traffic at all.
      h.store(addr, value)._4 mustBe empty
      // The silently-upgraded M line answers a recall with its dirty data.
      val r = h.injectProbe(addr, BreezeProbeOpcode.ProbeRecallInv)
      r.hasData mustBe true
      (r.data & word) mustBe value
    }
  }

  "a clean E line answers ProbeToS without data and degrades to S" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(1, 4)
      h.load(addr) // E
      val r = h.injectProbe(addr, BreezeProbeOpcode.ProbeToS)
      r.hasData mustBe false
      // The line is S now: a store must issue a GetM upgrade.
      val value = BigInt("fedcba9876543210", 16)
      h.store(addr, value)._4.map(_.opcode) mustBe Seq(GetM)
      val r2 = h.injectProbe(addr, BreezeProbeOpcode.ProbeRecallInv)
      r2.hasData mustBe true
      (r2.data & word) mustBe value
    }
  }

  "a dirty M line answers ProbeInv with data and is invalidated" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(2, 5)
      val value = BigInt("1122334455667788", 16)
      h.store(addr, value)
      val r = h.injectProbe(addr, BreezeProbeOpcode.ProbeInv)
      r.hasData mustBe true
      (r.data & word) mustBe value
      r.lineAddr mustBe lineBase(addr)
      // Invalidated: the next access misses again.
      h.load(addr)._4.map(_.opcode) mustBe Seq(GetS)
    }
  }

  "an S line store issues GetM and completes with a dataless upgrade grant" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      h.grantStateForGetS = BreezeGrantState.S.litValue
      h.getMHasData = false
      val addr = sramAddr(3, 6)
      h.load(addr)._4.map(_.opcode) mustBe Seq(GetS)
      val value = BigInt("00ddccbbaa998877", 16)
      h.store(addr, value)._4.map(_.opcode) mustBe Seq(GetM)
      h.load(addr)._1 mustBe value
      val r = h.injectProbe(addr, BreezeProbeOpcode.ProbeRecallInv)
      r.hasData mustBe true
      (r.data & word) mustBe value
    }
  }

  "srcHart tags requests and probe responses with the configured hart id" in {
    simulate(newDut(hartId = 3, hartIdWidth = 2)) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(4, 7)
      val value = BigInt("a5a5a5a5a5a5a5a5", 16)
      h.store(addr, value)
      h.reqLog.head.srcHart mustBe 3
      h.reqLog.head.lineAddr mustBe lineBase(addr)
      val r = h.injectProbe(addr, BreezeProbeOpcode.ProbeRecallInv, txnId = 3)
      r.srcHart mustBe 3
      r.txnId mustBe 3
      (r.data & word) mustBe value
    }
  }

  // ===== Transient races =====

  for (probeWithGrant <- Seq(true, false)) {
    s"refill installation preserves AMO data with probe at ${if (probeWithGrant) "grant" else "install"}" in {
      simulate(newDut()) { dut =>
        val h = new DCacheHomeModel(dut, new DTestMem)
        val coh = dut.io.coherence
        val addr = sramAddr(0, 12)
        val old = h.mem.readBytes(addr, 8)
        val increment = BigInt(7)
        h.holdGrant = true
        h.cpuStart(addr, BreezeMemOp.Amo, wdata = increment, amoFunc = BreezeAmoFunc.Add)
        var cycles = 0
        while (h.reqLog.isEmpty && cycles < 100) { h.step(); cycles += 1 }
        h.reqLog.map(_.opcode).toSeq mustBe Seq(GetM)
        h.step()

        def driveProbe(): Unit = {
          coh.probe.valid.poke(true.B)
          coh.probe.txnId.poke(3.U)
          coh.probe.lineAddr.poke(lineBase(addr).U)
          coh.probe.opcode.poke(BreezeProbeOpcode.ProbeRecallInv.litValue)
          coh.probe.ready.peek().litToBoolean mustBe true
        }
        if (probeWithGrant) driveProbe()
        h.holdGrant = false
        h.step() // Accept the data grant; the line has not been installed yet.
        h.grantsAccepted mustBe 1
        dut.io.cpu.rsp.valid.peek().litToBoolean mustBe false
        coh.grant.ready.peek().litToBoolean mustBe false
        if (probeWithGrant) coh.probe.valid.poke(false.B) else driveProbe()
        h.step() // Install using captured data; the model now drives invalid zeros.
        coh.probe.valid.poke(false.B)
        val (data, error, _) = h.cpuWait()
        error mustBe false
        data mustBe old

        cycles = 0
        while (!coh.probeResp.valid.peek().litToBoolean && cycles < 100) {
          h.step(); cycles += 1
        }
        coh.probeResp.valid.peek().litToBoolean mustBe true
        coh.probeResp.hasData.peek().litToBoolean mustBe true
        (coh.probeResp.lineData.peek().litValue & word) mustBe ((old + increment) & word)
        coh.probeResp.ready.poke(true.B)
        h.step()
        coh.probeResp.ready.poke(false.B)
        h.grantsAccepted mustBe 1
      }
    }
  }

  "upgrade race: a probe kills the S copy while GetM waits; the data grant repairs it" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      h.grantStateForGetS = BreezeGrantState.S.litValue
      val addr = sramAddr(0, 8)
      h.load(addr) // S
      val value = BigInt("deadbeefcafebabe", 16)

      h.holdGrant = true
      val logStart = h.reqLog.length
      h.cpuStart(addr, BreezeMemOp.Store, wdata = value)
      var cycles = 0
      while (h.reqLog.length == logStart && cycles < 200) { h.step(); cycles += 1 }
      h.reqLog(logStart).opcode mustBe GetM

      // Another hart's transaction invalidated our S copy first.
      val r = h.injectProbe(addr, BreezeProbeOpcode.ProbeInv)
      r.hasData mustBe false

      // The Home saw the requester was no longer a sharer: GrantM carries data.
      h.holdGrant = false
      val (_, error, ack) = h.cpuWait()
      error mustBe false
      ack mustBe true
      // The store merged into the GRANT data, not into the dead local copy.
      h.load(addr)._1 mustBe value
      val r2 = h.injectProbe(addr, BreezeProbeOpcode.ProbeRecallInv)
      r2.hasData mustBe true
      (r2.data & word) mustBe value
    }
  }

  "Put cancel: a probe that takes the parked victim cancels the PutM" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      def storedVal(tag: Int): BigInt = BigInt(tag + 1) * BigInt("1111111111111111", 16) / 15
      for (tag <- 0 until 4) h.store(sramAddr(tag, 9), storedVal(tag))

      // The fifth line forces an eviction; park the request channel so the
      // PutM cannot be handshaken yet.
      h.holdReqReady = true
      val logStart = h.reqLog.length
      h.cpuStart(sramAddr(4, 9), BreezeMemOp.Load)
      val victimLine = h.peekParkedRequest(BreezeCoherenceOpcode.PutM)
      val victimTag = ((victimLine - BigInt(0x11000000L)) >> 11).toInt

      // The Home recalls exactly that victim for another transaction.
      val r = h.injectProbe(victimLine, BreezeProbeOpcode.ProbeRecallInv)
      r.hasData mustBe true
      (r.data & word) mustBe storedVal(victimTag)

      // The parked PutM is cancelled (never fires); the miss proceeds.
      h.holdReqReady = false
      val (data, error, _) = h.cpuWait()
      error mustBe false
      data mustBe h.mem.readBytes(sramAddr(4, 9), 8)
      val delta = h.reqLog.slice(logStart, h.reqLog.length)
      delta.count(x => x.opcode == PutM || x.opcode == PutS) mustBe 0
      delta.map(_.opcode) mustBe Seq(GetS)
    }
  }

  "CPU pulse arriving during an unsolicited probe is queued and served after it" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val coh = dut.io.coherence
      val probeAddr = sramAddr(1, 10)
      val cpuAddr = sramAddr(2, 11)

      coh.probe.valid.poke(true.B)
      coh.probe.txnId.poke(2.U)
      coh.probe.lineAddr.poke(lineBase(probeAddr).U)
      coh.probe.opcode.poke(BreezeProbeOpcode.ProbeInv.litValue)
      assert(coh.probe.ready.peek().litToBoolean)
      h.step()
      coh.probe.valid.poke(false.B)
      h.step() // Idle observes the pending probe and enters the probe FSM.

      h.cpuStart(cpuAddr, BreezeMemOp.Load)

      var cycles = 0
      while (!coh.probeResp.valid.peek().litToBoolean && cycles < 200) { h.step(); cycles += 1 }
      assert(coh.probeResp.valid.peek().litToBoolean, "probe response timed out")
      coh.probeResp.ready.poke(true.B)
      h.step()
      coh.probeResp.ready.poke(false.B)

      val (data, error, _) = h.cpuWait()
      error mustBe false
      data mustBe h.mem.readBytes(cpuAddr, 8)
    }
  }

  // ===== LR/SC =====

  "LR/SC succeeds locally on an E line without a GetM" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(0, 12)
      val value = BigInt("0102030405060708", 16)
      val (old, err, _, reqsLr) = h.cpu(addr, BreezeMemOp.Lr)
      err mustBe false
      old mustBe h.mem.readBytes(addr, 8)
      reqsLr.map(_.opcode) mustBe Seq(GetS)
      val (sc, scErr, _, reqsSc) = h.cpu(addr, BreezeMemOp.Sc, wdata = value)
      scErr mustBe false
      sc mustBe 0
      reqsSc mustBe empty // E hit: the SC write is purely local
      h.load(addr)._1 mustBe value
    }
  }

  "LR/SC succeeds through a GetM upgrade on an S line" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      h.grantStateForGetS = BreezeGrantState.S.litValue
      val addr = sramAddr(1, 13)
      val value = BigInt("1112131415161718", 16)
      h.cpu(addr, BreezeMemOp.Lr)._2 mustBe false
      val (sc, _, _, reqs) = h.cpu(addr, BreezeMemOp.Sc, wdata = value)
      sc mustBe 0
      reqs.map(_.opcode) mustBe Seq(GetM)
      h.load(addr)._1 mustBe value
    }
  }

  "SC without a reservation fails fast with no traffic and no write" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(2, 14)
      val before = h.mem.readBytes(addr, 8)
      val (sc, err, ack, reqs) = h.cpu(addr, BreezeMemOp.Sc, wdata = BigInt(0x5555))
      err mustBe false
      ack mustBe false
      sc mustBe 1
      reqs mustBe empty
      h.load(addr)._1 mustBe before
    }
  }

  "SC with a mismatching size fails even at the reserved address" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(3, 15)
      h.cpu(addr, BreezeMemOp.Lr, sizeLog2 = 2)._2 mustBe false // LR.W
      val (sc, _, _, reqs) = h.cpu(addr, BreezeMemOp.Sc, sizeLog2 = 3, wdata = 1) // SC.D
      sc mustBe 1
      reqs mustBe empty
    }
  }

  "a probe on the reserved line clears the reservation (SC fails without GetM)" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(4, 16)
      h.cpu(addr, BreezeMemOp.Lr)._2 mustBe false // E line + reservation
      h.injectProbe(addr, BreezeProbeOpcode.ProbeToS).hasData mustBe false
      // Line still resident (S), but the reservation died with the probe.
      val before = h.mem.readBytes(addr, 8)
      val (sc, _, _, reqs) = h.cpu(addr, BreezeMemOp.Sc, wdata = BigInt(0x7777))
      sc mustBe 1
      reqs mustBe empty
      h.load(addr)._1 mustBe before
    }
  }

  "SC reservation killed while the GetM waits: fail, no write, clean E install" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      h.grantStateForGetS = BreezeGrantState.S.litValue
      val addr = sramAddr(5, 17)
      val before = h.mem.readBytes(addr, 8)
      h.cpu(addr, BreezeMemOp.Lr)._2 mustBe false // S line + reservation

      h.holdGrant = true
      val logStart = h.reqLog.length
      h.cpuStart(addr, BreezeMemOp.Sc, wdata = BigInt(0x9999))
      var cycles = 0
      while (h.reqLog.length == logStart && cycles < 200) { h.step(); cycles += 1 }
      h.reqLog(logStart).opcode mustBe GetM

      h.injectProbe(addr, BreezeProbeOpcode.ProbeInv) // kills copy + reservation
      h.holdGrant = false
      val (sc, err, _) = h.cpuWait()
      err mustBe false
      sc mustBe 1
      // No write happened; the granted line was installed as a clean E copy:
      // the read hits the grant data and a store stays silent (no GetM).
      val delta = h.reqLog.slice(logStart, h.reqLog.length)
      delta.map(_.opcode) mustBe Seq(GetM)
      h.load(addr)._1 mustBe before
      h.store(addr, BigInt(0xabcd))._4 mustBe empty
    }
  }

  "resKill clears the reservation" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(0, 18)
      h.cpu(addr, BreezeMemOp.Lr)._2 mustBe false
      h.resKillPulse()
      val (sc, _, _, reqs) = h.cpu(addr, BreezeMemOp.Sc, wdata = 1)
      sc mustBe 1
      reqs mustBe empty
    }
  }

  // ===== AMO =====

  "AMO hit on E: old value returned, new value written, no coherence traffic" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(1, 19)
      h.load(addr) // E
      val old = h.mem.readBytes(addr, 8)
      val (data, err, _, reqs) = h.cpu(addr, BreezeMemOp.Amo, wdata = BigInt(5),
        amoFunc = BreezeAmoFunc.Add)
      err mustBe false
      data mustBe old
      reqs mustBe empty // E hit: silent upgrade, local RMW
      h.load(addr)._1 mustBe ((old + 5) & word)
      // The RMW made the line M: a recall returns the merged data.
      val r = h.injectProbe(addr, BreezeProbeOpcode.ProbeRecallInv)
      r.hasData mustBe true
      (r.data & word) mustBe ((old + 5) & word)
    }
  }

  "AMO.W modifies only the addressed 32-bit half and returns the aligned old word" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(2, 20)
      h.load(addr)
      val old = h.mem.readBytes(addr, 8)
      // AMOADD.W on the HIGH half (addr+4).
      val (data, err, _, _) = h.cpu(addr + 4, BreezeMemOp.Amo, sizeLog2 = 2,
        wdata = BigInt(1), amoFunc = BreezeAmoFunc.Add)
      err mustBe false
      data mustBe old // the raw aligned 64-bit old word (backend extracts)
      val expectedHigh = ((old >> 32) + 1) & ((BigInt(1) << 32) - 1)
      h.load(addr)._1 mustBe ((expectedHigh << 32) | (old & ((BigInt(1) << 32) - 1)))
    }
  }

  "AMO miss allocates with GetM and returns the pre-modification word" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(3, 21)
      val old = h.mem.readBytes(addr, 8)
      val swapped = BigInt("00000000cafebabe", 16)
      val (data, err, _, reqs) = h.cpu(addr, BreezeMemOp.Amo, wdata = swapped,
        amoFunc = BreezeAmoFunc.Swap)
      err mustBe false
      data mustBe old
      reqs.map(_.opcode) mustBe Seq(GetM)
      h.load(addr)._1 mustBe swapped
    }
  }

  "AMO on an S line upgrades with GetM before the RMW" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      h.grantStateForGetS = BreezeGrantState.S.litValue
      val addr = sramAddr(4, 22)
      h.load(addr) // S
      val old = h.mem.readBytes(addr, 8)
      val (data, err, _, reqs) = h.cpu(addr, BreezeMemOp.Amo, wdata = old,
        amoFunc = BreezeAmoFunc.MaxU)
      err mustBe false
      data mustBe old
      reqs.map(_.opcode) mustBe Seq(GetM)
      h.load(addr)._1 mustBe old // maxu(old, old) == old
    }
  }

  "atomics on a device region fail with an access error and no traffic" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      for (op <- Seq(BreezeMemOp.Lr, BreezeMemOp.Sc, BreezeMemOp.Amo)) {
        val (_, err, _, reqs) = h.cpu(BigInt("02000000", 16), op, wdata = 1)
        err mustBe true
        reqs mustBe empty
      }
      h.mmioLog mustBe empty
    }
  }

  "eviction of the reserved line clears the reservation" in {
    simulate(newDut()) { dut =>
      val h = new DCacheHomeModel(dut, new DTestMem)
      val addr = sramAddr(0, 23)
      h.cpu(addr, BreezeMemOp.Lr)._2 mustBe false
      // Fill the set with three more lines, then overflow it so the reserved
      // line (way 0, PLRU victim after the touches) is evicted.
      for (tag <- 1 until 4) h.load(sramAddr(tag, 23))
      h.load(sramAddr(4, 23)) // evicts way 0 = the reserved line
      val (sc, _, _, reqs) = h.cpu(addr, BreezeMemOp.Sc, wdata = 1)
      sc mustBe 1
      reqs mustBe empty
    }
  }
}

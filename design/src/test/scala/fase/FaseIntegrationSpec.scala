package flow.fase

import chisel3._
import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import flow.top.BreezeMulticoreClusterWishbone
import flow.bus.LiteXWishboneMasterIO
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable

/** Transport starts at decoded command records, not at the physical JTAG TAP.
  * Everything downstream is the real Linux-profile core, MMU, L1s and L2.
  */
class FaseIntegrationSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  private val boot = BigInt("80000000", 16)
  private def addi(rd: Int, rs: Int, imm: Int): BigInt =
    (BigInt(imm & 4095) << 20) | (BigInt(rs) << 15) | (BigInt(rd) << 7) | 0x13
  private def csr(rd: Int, address: Int, rs: Int, f: Int = 1): BigInt =
    (BigInt(address) << 20) | (BigInt(rs) << 15) | (BigInt(f) << 12) | (BigInt(rd) << 7) | 0x73

  "command decode controls a Linux core, diagnoses stalls and launches coherent DDR code" in {
    val cfg = BreezeClusterPresets.fromName("single").copy(privilegeProfile = PrivilegeProfile.Linux)
    simulate(new BreezeMulticoreClusterWishbone(cfg, enableTandem = true, useFASE = true)) { d =>
      val h = d.io.fase.get(0)
      val mem = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      def put(a: BigInt, v: BigInt, n: Int): Unit =
        (0 until n).foreach(i => mem(a + i) = ((v >> (8 * i)) & 255).toInt)
      def get(a: BigInt, n: Int): BigInt =
        (0 until n).foldLeft(BigInt(0))((v, i) => v | (BigInt(mem(a + i)) << (8 * i)))
      put(boot, addi(10, 10, 1), 4)
      put(boot + 4, BigInt("ffdff06f", 16), 4) // jal x0,-4
      val target = boot + 0x400
      put(target, addi(20, 0, 11), 4)
      put(target + 4, BigInt("0000006f", 16), 4)
      var blockAddress: Option[BigInt] = None
      var cycles = 0
      val commits = mutable.ArrayBuffer.empty[(BigInt, BigInt)]
      def bus(b: LiteXWishboneMasterIO): Unit = {
        val address = b.adr.peek().litValue << 3
        val active = b.cyc.peek().litToBoolean && b.stb.peek().litToBoolean
        val ack = active && !blockAddress.contains(address)
        b.ack.poke(ack.B); b.err.poke(false.B); b.dat_r.poke(get(address, 8).U)
        if (ack && b.we.peek().litToBoolean) {
          val data = b.dat_w.peek().litValue
          val mask = b.sel.peek().litValue
          (0 until 8).filter(mask.testBit).foreach(i => put(address + i, data >> (8 * i), 1))
        }
      }
      def step(n: Int = 1): Unit = (0 until n).foreach { _ =>
        bus(d.io.memoryWishbone); bus(d.io.mmioWishbone)
        if (d.io.retire(0).valid.peek().litToBoolean)
          commits += ((d.io.retire(0).pc.peek().litValue, d.io.retire(0).inst.peek().litValue))
        d.clock.step(); cycles += 1
      }
      def until(why: String, limit: Int = 20000)(p: => Boolean): Unit = {
        var n = 0
        while (!p && n < limit) { step(); n += 1 }
        assert(p, s"$why timed out at cycle $cycles")
      }
      def cmd(op: Int, data: BigInt = 0, index: Int = 0, pc: BigInt = 0,
              error: Boolean = false): BigInt = {
        h.cmd.valid.poke(false.B); h.rsp.ready.poke(true.B)
        until("command ready") { h.cmd.ready.peek().litToBoolean }
        h.cmd.bits.opcode.poke(op.U); h.cmd.bits.index.poke(index.U)
        h.cmd.bits.data.poke(data.U); h.cmd.bits.pc.poke(pc.U)
        h.cmd.valid.poke(true.B); step(); h.cmd.valid.poke(false.B)
        until("command response") { h.rsp.valid.peek().litToBoolean }
        h.rsp.bits.error.expect(error.B)
        val result = h.rsp.bits.data.peek().litValue
        step(); result
      }
      def status(): BigInt = cmd(FaseOpcode.Status)
      def halt(): Unit = {
        cmd(FaseOpcode.Halt)
        var n = 0
        while ((status() & 6) != 6 && n < 1000) { n += 1 }
        assert(n < 1000, "halt did not drain")
      }
      def wr(r: Int, v: BigInt): Unit = { cmd(FaseOpcode.WriteReg, v, r) }
      def rd(r: Int): BigInt = cmd(FaseOpcode.ReadReg, index = r)
      def exec(inst: BigInt, expectFault: Boolean = false): Unit = {
        cmd(FaseOpcode.Exec, inst, pc = boot + 0x1000)
        var s = status(); var n = 0
        while ((s & 8) != 0 && n < 10000) { s = status(); n += 1 }
        assert(n < 10000, "injected instruction did not complete")
        assert((s & 16) != 0, "completion was not latched")
        ((s & 32) != 0) mustBe expectFault
      }
      def snap(i: Int): BigInt = cmd(FaseOpcode.ReadSnapshot, index = i)
      h.cmd.valid.poke(false.B); h.rsp.ready.poke(true.B)
      h.cmd.bits.opcode.poke(0.U); h.cmd.bits.index.poke(0.U)
      h.cmd.bits.data.poke(0.U); h.cmd.bits.pc.poke(0.U)
      d.io.resetAddr.poke(boot.U); d.io.msip(0).poke(false.B); d.io.mtip(0).poke(false.B)
      d.io.externalInterrupts(0).poke(0.U); d.io.supervisorExternalInterrupts(0).poke(false.B)
      d.io.time.poke(0.U)
      Seq(d.io.memoryWishbone, d.io.mmioWishbone).foreach { b =>
        b.ack.poke(false.B); b.err.poke(false.B); b.dat_r.poke(0.U)
      }
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      until("normal boot") { commits.count(_._1 == boot) >= 3 }
      cmd(FaseOpcode.WriteReg, 99, 10, error = true)
      halt()
      val before = rd(10); before must be >= BigInt(3)
      step(30); rd(10) mustBe before
      wr(5, 40); rd(5) mustBe BigInt(40)
      exec(addi(6, 5, 2)); rd(6) mustBe BigInt(42)
      exec(BigInt("006283b3", 16)) // add x7,x5,x6: both decode read ports
      rd(7) mustBe BigInt(82)
      wr(0, 123); rd(0) mustBe BigInt(0)
      cmd(255, error = true)
      // Backpressure must hold response stable and must not repeat a write.
      h.rsp.ready.poke(false.B)
      h.cmd.bits.opcode.poke(FaseOpcode.Status.U); h.cmd.valid.poke(true.B)
      step(); h.cmd.valid.poke(false.B)
      val held = h.rsp.bits.data.peek().litValue
      step(5); h.rsp.valid.expect(true.B); h.rsp.bits.data.expect(held.U)
      h.cmd.ready.expect(false.B); h.rsp.ready.poke(true.B); step()
      // Real instruction cache contains the OLD DDR code first.
      cmd(FaseOpcode.Launch, pc = target)
      until("old code execution") { commits.exists(_._1 == target) }
      halt(); rd(20) mustBe BigInt(11)
      // Patch via CPU store/D-cache, then execute the real fence.i implementation.
      wr(1, target); wr(2, (BigInt("0000006f", 16) << 32) | addi(20, 0, 77))
      exec(BigInt("0020b023", 16)) // sd x2,0(x1)
      exec(BigInt("0000100f", 16)) // fence.i
      val mark = commits.size
      cmd(FaseOpcode.Launch, pc = target)
      until("new code execution") { commits.drop(mark).exists(_._1 == target) }
      halt(); rd(20) mustBe BigInt(77)
      // Illegal instruction is reported, not mistaken for successful drain.
      exec(BigInt("ffffffff", 16), expectFault = true)
      cmd(FaseOpcode.Cause) mustBe BigInt(2)
      cmd(FaseOpcode.Tval) mustBe BigInt("ffffffff", 16)
      // Programmatically return to S mode, then externally take over in M again.
      wr(1, target); exec(csr(0, 0x341, 1))
      wr(1, 0x800); exec(csr(0, 0x300, 1))
      exec(BigInt("30200073", 16))
      cmd(FaseOpcode.Snapshot); snap(16) mustBe BigInt(1)
      cmd(FaseOpcode.NextPc) mustBe target
      cmd(FaseOpcode.Launch, pc = target)
      step(100); halt()
      cmd(FaseOpcode.Snapshot)
      snap(16) mustBe BigInt(3); snap(36) mustBe BigInt(1)
      // A never-responding physical read must not block the command interface.
      val stuck = boot + 0x20000
      blockAddress = Some(stuck)
      wr(1, stuck)
      cmd(FaseOpcode.Exec, BigInt("0000b183", 16), pc = boot + 0x1000) // ld x3,0(x1)
      step(100)
      assert((status() & 8) != 0)
      cmd(FaseOpcode.Snapshot)
      assert((snap(4) & 32) != 0, "missing outstanding load diagnostic")
      snap(5) mustBe stuck
      cmd(FaseOpcode.Launch, pc = boot, error = true)
      cmd(FaseOpcode.WriteReg, 1, 1, error = true)
      blockAddress = None
      var n = 0
      while ((status() & 8) != 0 && n < 1000) { n += 1 }
      assert(n < 1000); rd(3) mustBe BigInt(0)
      println(s"FASE_COMMAND_CPU_PASS cycles=$cycles commits=${commits.size}")
    }
  }
}

package flow.core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.PrivilegeProfile
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCsrPipelineSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val mask = (BigInt(1) << 64) - 1

  private def idle(dut: CSRFile): Unit = {
    dut.io.csr_addr.poke(0.U)
    dut.io.csr_cmd.poke(CSR_CMD.RS.U)
    dut.io.csr_reg_data.poke(0.U)
    dut.io.rs1_id.poke(0.U)
    dut.io.rd_id.poke(1.U)
    dut.io.commit_valid.poke(false.B)
    dut.io.commit_write_en.poke(false.B)
    dut.io.commit_addr.poke(0.U)
    dut.io.commit_wdata.poke(0.U)
    dut.io.fp_commit_valid.poke(false.B)
    dut.io.fp_flags.poke(0.U)
    dut.io.retire_valid.poke(false.B)
    dut.io.machineTimerInterrupt.poke(false.B)
    dut.io.machineSoftwareInterrupt.poke(false.B)
    dut.io.machineExternalInterrupt.poke(false.B)
    dut.io.supervisorExternalInterrupt.poke(false.B)
    dut.io.time.poke(0.U)
    dut.io.trap.valid.poke(false.B)
    dut.io.trap.is_interrupt.poke(false.B)
    dut.io.trap.cause.poke(0.U)
    dut.io.trap.pc.poke(0.U)
    dut.io.trap.tval.poke(0.U)
    dut.io.mret_commit.poke(false.B)
    dut.io.sret_commit.poke(false.B)
    eventPorts(dut).foreach(_.poke(false.B))
  }

  private def eventPorts(dut: CSRFile): Seq[Bool] = Seq(
    dut.io.hpmEvents.controlRetired, dut.io.hpmEvents.controlTaken,
    dut.io.hpmEvents.predictionMiss, dut.io.hpmEvents.icacheAccess,
    dut.io.hpmEvents.icacheMiss, dut.io.hpmEvents.dcacheAccess,
    dut.io.hpmEvents.dcacheMiss, dut.io.hpmEvents.dcacheUncached,
    dut.io.hpmEvents.memStallCycle, dut.io.hpmEvents.loadUseStall)

  "pipelined HPM matches an immediate-count model at every CSR-visible cycle" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      idle(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      val counts = Array.fill(8)(BigInt(0))
      val selectors = Array.fill(8)(0)
      var inhibit = BigInt(0)
      var cycle = BigInt(0)
      var instret = BigInt(0)
      var coreinst = BigInt(0)
      var steps = 0

      def read(address: Int, expected: BigInt): Unit = {
        dut.io.csr_addr.poke(address.U)
        dut.io.csr_illegal.expect(false.B)
        dut.io.csr_old_data.expect(expected.U)
      }
      def check(): Unit = {
        for (i <- 0 until 8) {
          read(CSRMAP.mhpmcounter3 + i, counts(i))
          read(CSRMAP.hpmcounter3 + i, counts(i))
          read(CSRMAP.mhpmevent3 + i, selectors(i))
        }
        read(CSRMAP.mcycle, cycle)
        read(CSRMAP.cycle, cycle)
        read(CSRMAP.minstret, instret)
        read(CSRMAP.instret, instret)
        read(CSRMAP.coreinst, coreinst)
        read(CSRMAP.mcountinhibit, inhibit)
      }
      // The reference has no pending state: it counts each event immediately
      // using the pre-edge configuration and explicit-write priority.
      def tick(events: Int = 0, write: Option[(Int, BigInt)] = None,
               retire: Boolean = false, trap: Boolean = false,
               valid: Boolean = true, writeEnable: Boolean = true): Unit = {
        for ((port, bit) <- eventPorts(dut).zipWithIndex)
          port.poke(((events & (1 << bit)) != 0).B)
        dut.io.retire_valid.poke(retire.B)
        dut.io.trap.valid.poke(trap.B)
        dut.io.commit_valid.poke((write.nonEmpty && valid).B)
        dut.io.commit_write_en.poke(writeEnable.B)
        dut.io.commit_addr.poke(write.map(_._1).getOrElse(0).U)
        dut.io.commit_wdata.poke(write.map(_._2).getOrElse(BigInt(0)).U)
        val accepted = write.filter(_ => valid && writeEnable && !trap)
        def written(address: Int): Option[BigInt] = accepted.collect {
          case (`address`, value) => value
        }
        for (i <- 0 until 8) {
          val event = selectors(i) != 0 && (events & (1 << (selectors(i) - 1))) != 0
          counts(i) = written(CSRMAP.mhpmcounter3 + i).getOrElse(
            (counts(i) + (if (!inhibit.testBit(i + 3) && event) 1 else 0)) & mask)
        }
        cycle = written(CSRMAP.mcycle).getOrElse((cycle + (if (!inhibit.testBit(0)) 1 else 0)) & mask)
        instret = written(CSRMAP.minstret).getOrElse(
          (instret + (if (retire && !inhibit.testBit(2)) 1 else 0)) & mask)
        coreinst = (coreinst + (if (retire) 1 else 0)) & mask
        for (i <- 0 until 8) written(CSRMAP.mhpmevent3 + i).foreach { value =>
          selectors(i) = if (value <= 10) value.toInt else 0
        }
        written(CSRMAP.mcountinhibit).foreach(value => inhibit = value & 0x7fd)
        dut.clock.step()
        dut.io.trap.valid.poke(false.B)
        dut.io.commit_valid.poke(false.B)
        steps += 1
        withClue(s"after cycle $steps: ") { check() }
      }
      check()
      // Exercise every selector on every counter, including continuous events
      // across reconfiguration and inhibited/enabled boundaries.
      for (event <- 1 to 10; i <- 0 until 8)
        tick(0x3ff, Some((CSRMAP.mhpmevent3 + i, BigInt(event))))
      tick(0x3ff, Some((CSRMAP.mcountinhibit, mask)))
      tick(0x3ff)
      tick(0x3ff, Some((CSRMAP.mcountinhibit, BigInt(0))))
      // A live pending event, wraparound, and a software overwrite in adjacent cycles.
      for (i <- 0 until 8) {
        tick(0x3ff, Some((CSRMAP.mhpmcounter3 + i, mask)))
        tick(0x3ff)
        tick(0x3ff, Some((CSRMAP.mhpmcounter3 + i, BigInt(42))))
        tick()
      }
      for (value <- Seq(BigInt(11), BigInt(0x101), (BigInt(1) << 63) | 10, mask))
        for (i <- 0 until 8) tick(0x3ff, Some((CSRMAP.mhpmevent3 + i, value)))
      tick(write = Some((CSRMAP.mcycle, mask)))
      tick(retire = true, write = Some((CSRMAP.minstret, mask)))
      tick(retire = true)
      val random = new scala.util.Random(0x435352)
      for (_ <- 0 until 400) {
        val i = random.nextInt(8)
        val write = random.nextInt(6) match {
          case 0 => Some((CSRMAP.mhpmevent3 + i, BigInt(random.nextInt(13))))
          case 1 => Some((CSRMAP.mhpmcounter3 + i, BigInt(64, random)))
          case 2 => Some((CSRMAP.mcountinhibit, BigInt(random.nextInt(2048))))
          case 3 => Some((CSRMAP.mcycle, BigInt(64, random)))
          case 4 => Some((CSRMAP.minstret, BigInt(64, random)))
          case _ => None
        }
        tick(random.nextInt(1024), write, random.nextBoolean(),
          trap = random.nextInt(7) == 0, valid = random.nextInt(9) != 0,
          writeEnable = random.nextInt(9) != 0)
      }
      // Reset while the last event is still pending must clear the full view.
      tick(write = Some((CSRMAP.mcountinhibit, BigInt(0))))
      tick(write = Some((CSRMAP.mhpmevent3, BigInt(1))))
      tick(events = 1)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      counts.indices.foreach(i => { counts(i) = 0; selectors(i) = 0 })
      inhibit = 0; cycle = 0; instret = 0; coreinst = 0
      check()
    }
  }

  "parallel CSR banks select distinct full-width values and preserve read-modify-write" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      idle(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      def commit(address: Int, value: BigInt): Unit = {
        dut.io.commit_valid.poke(true.B)
        dut.io.commit_write_en.poke(true.B)
        dut.io.commit_addr.poke(address.U)
        dut.io.commit_wdata.poke(value.U)
        dut.clock.step()
        dut.io.commit_valid.poke(false.B)
      }
      commit(CSRMAP.mcountinhibit, 0x7fd)
      val values = Seq(
        CSRMAP.mscratch -> BigInt("123456789abcdef0", 16),
        CSRMAP.sscratch -> BigInt("fedcba9876543210", 16),
        CSRMAP.printer -> BigInt("876543210fedcba9", 16),
        CSRMAP.mcycle -> BigInt("ffffffffabcdef01", 16),
        CSRMAP.minstret -> BigInt("1000000012345678", 16),
        CSRMAP.mhpmcounter3 -> BigInt("8000000011111111", 16),
        CSRMAP.pmpaddr0 -> BigInt("23456789abcdef", 16),
        CSRMAP.satp -> BigInt("8123456789abcdef", 16))
      values.foreach { case (address, value) => commit(address, value) }
      val operand = BigInt("55555555aaaaaaaa", 16)
      for ((address, value) <- values ++ Seq(
          CSRMAP.cycle -> values(3)._2, CSRMAP.instret -> values(4)._2,
          CSRMAP.hpmcounter3 -> values(5)._2)) {
        dut.io.csr_addr.poke(address.U)
        dut.io.csr_reg_data.poke(operand.U)
        dut.io.rs1_id.poke(1.U)
        for ((command, expected) <- Seq(
            CSR_CMD.RW -> operand, CSR_CMD.RS -> (value | operand),
            CSR_CMD.RC -> (value & (mask ^ operand)))) {
          dut.io.csr_cmd.poke(command.U)
          dut.io.csr_old_data.expect(value.U)
          dut.io.csr_new_data.expect(expected.U)
        }
      }
      dut.io.csr_cmd.poke(CSR_CMD.RS.U)
      dut.io.rs1_id.poke(0.U)
      for (address <- Seq(0x7ff, CSRMAP.pmpaddr0 + 8, CSRMAP.pmpcfg2)) {
        dut.io.csr_addr.poke(address.U)
        dut.io.csr_old_data.expect(0.U)
        dut.io.csr_illegal.expect((address == 0x7ff).B)
      }
    }
  }
}

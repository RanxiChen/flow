package flow.mcu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.air.AirCore
import flow.wisp.WispCore
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable

class MachineInterruptSpec extends AnyFreeSpec with ChiselSim {
  private def i(opcode: Int, rd: Int, funct3: Int, rs1: Int, imm: Int): Int =
    ((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode

  private val program = Map[BigInt, BigInt](
    BigInt(0x00 / 4) -> BigInt(i(0x13, 1, 0, 0, 0x40) & 0xffffffffL), // li x1,0x40
    BigInt(0x04 / 4) -> BigInt(i(0x73, 0, 1, 1, 0x305) & 0xffffffffL), // csrw mtvec,x1
    BigInt(0x08 / 4) -> BigInt(i(0x13, 1, 0, 0, 0x880) & 0xffffffffL), // li x1,MTIE|MEIE
    BigInt(0x0c / 4) -> BigInt(i(0x73, 0, 1, 1, 0x304) & 0xffffffffL), // csrw mie,x1
    BigInt(0x10 / 4) -> BigInt(i(0x13, 1, 0, 0, 8) & 0xffffffffL),    // li x1,MIE
    BigInt(0x14 / 4) -> BigInt(i(0x73, 0, 2, 1, 0x300) & 0xffffffffL), // csrs mstatus,x1
    BigInt(0x18 / 4) -> BigInt(0x10500073L),                           // wfi
    BigInt(0x1c / 4) -> BigInt(i(0x13, 5, 0, 5, 1) & 0xffffffffL),    // addi x5,x5,1
    BigInt(0x20 / 4) -> BigInt(0x0000006fL),                           // jal x0,0
    BigInt(0x40 / 4) -> BigInt(i(0x13, 6, 0, 6, 1) & 0xffffffffL),    // addi x6,x6,1
    BigInt(0x44 / 4) -> BigInt(0x30200073L))                           // mret

  "Air takes MEIP at an instruction boundary and resumes after MRET" in {
    simulate(new AirCore(withCompressed = true, withTrace = true)) { dut =>
      dut.io.timerIrq.poke(false.B); dut.io.externalIrq.poke(false.B)
      dut.io.wb.ack.poke(false.B); dut.io.wb.err.poke(false.B); dut.io.wb.dat_r.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      var wfiRetired = false
      var trapSeen = false
      var handlerSeen = false
      var resumed = false
      var cycles = 0
      while (!resumed && cycles < 5000) {
        val active = dut.io.wb.cyc.peek().litToBoolean && dut.io.wb.stb.peek().litToBoolean
        val address = dut.io.wb.adr.peekValue().asBigInt
        dut.io.wb.ack.poke(active.B)
        dut.io.wb.dat_r.poke(program.getOrElse(address, BigInt(0)).U)
        dut.clock.step()
        if (dut.io.trace.valid.peek().litToBoolean) {
          val pc = dut.io.trace.pc.peekValue().asBigInt
          val trap = dut.io.trace.trap.peek().litToBoolean
          if (trap) {
            assert(dut.io.trace.cause.peekValue().asBigInt ==
              (BigInt(1) << 63 | 11), "Air reported the wrong interrupt cause")
            assert(pc == 0x1c, s"Air interrupt mepc boundary was 0x${pc.toString(16)}")
            trapSeen = true
            dut.io.externalIrq.poke(false.B)
          } else {
            if (pc == 0x18) { wfiRetired = true; dut.io.externalIrq.poke(true.B) }
            if (pc == 0x40) handlerSeen = true
            if (pc == 0x1c) resumed = true
          }
        }
        cycles += 1
      }
      assert(wfiRetired && trapSeen && handlerSeen && resumed,
        s"Air interrupt flow incomplete after $cycles cycles")
    }
  }

  "Wisp takes MTIP from WFI and resumes after MRET" in {
    simulate(new WispCore(withTrace = true)) { dut =>
      dut.io.timerIrq.poke(false.B); dut.io.externalIrq.poke(false.B)
      dut.io.wb.ack.poke(false.B); dut.io.wb.err.poke(false.B); dut.io.wb.dat_r.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      var wfiFetched = false
      var quietCycles = 0
      var trapSeen = false
      var handlerSeen = false
      var resumed = false
      var cycles = 0
      while (!resumed && cycles < 5000) {
        val active = dut.io.wb.cyc.peek().litToBoolean && dut.io.wb.stb.peek().litToBoolean
        val address = dut.io.wb.adr.peekValue().asBigInt
        if (active && address == 0x18 / 4) wfiFetched = true
        if (wfiFetched && !active) quietCycles += 1
        if (quietCycles == 3) dut.io.timerIrq.poke(true.B)
        dut.io.wb.ack.poke(active.B)
        dut.io.wb.dat_r.poke(program.getOrElse(address, BigInt(0)).U)
        dut.clock.step()
        if (dut.io.trace.valid.peek().litToBoolean) {
          val pc = dut.io.trace.pc.peekValue().asBigInt
          val trap = dut.io.trace.trap.peek().litToBoolean
          if (trap) {
            assert(dut.io.trace.cause.peekValue().asBigInt ==
              (BigInt(1) << 63 | 7), "Wisp reported the wrong interrupt cause")
            assert(pc == 0x1c, s"Wisp interrupt mepc boundary was 0x${pc.toString(16)}")
            trapSeen = true
            dut.io.timerIrq.poke(false.B)
          } else {
            if (pc == 0x40) handlerSeen = true
            if (pc == 0x1c) resumed = true
          }
        }
        cycles += 1
      }
      assert(wfiFetched && trapSeen && handlerSeen && resumed,
        s"Wisp interrupt flow incomplete after $cycles cycles")
    }
  }
}

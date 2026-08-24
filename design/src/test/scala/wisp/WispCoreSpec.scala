package flow.wisp

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable
import java.nio.file.{Files, Paths}

class WispCoreSpec extends AnyFreeSpec with ChiselSim {
  private def i(opcode: Int, rd: Int, funct3: Int, rs1: Int, imm: Int): Int =
    ((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode
  private def r(opcode: Int, rd: Int, funct3: Int, rs1: Int, rs2: Int, funct7: Int = 0): Int =
    (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode
  private def s(opcode: Int, funct3: Int, rs1: Int, rs2: Int, imm: Int): Int =
    (((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) |
      (funct3 << 12) | ((imm & 0x1f) << 7) | opcode
  private def b(funct3: Int, rs1: Int, rs2: Int, imm: Int): Int =
    (((imm >> 12) & 1) << 31) | (((imm >> 5) & 0x3f) << 25) | (rs2 << 20) |
      (rs1 << 15) | (funct3 << 12) | (((imm >> 1) & 0xf) << 8) |
      (((imm >> 11) & 1) << 7) | 0x63

  "execute a continuous RV64I/Zicsr/Zifencei program over one Wishbone master" in {
    simulate(new WispCore(resetVector = 0, withTrace = true)) { dut =>
      dut.io.timerIrq.poke(false.B); dut.io.externalIrq.poke(false.B)
      val words = mutable.Map[BigInt, BigInt](
        BigInt(0)  -> BigInt(i(0x13, 1, 0, 0, 5) & 0xffffffffL),       // addi x1,x0,5
        BigInt(1)  -> BigInt(i(0x13, 2, 0, 0, 7) & 0xffffffffL),       // addi x2,x0,7
        BigInt(2)  -> BigInt(r(0x33, 3, 0, 1, 2) & 0xffffffffL),       // add x3,x1,x2
        BigInt(3)  -> BigInt(s(0x23, 2, 0, 3, 0x100) & 0xffffffffL),   // sw x3,0x100(x0)
        BigInt(4)  -> BigInt(i(0x03, 4, 2, 0, 0x100) & 0xffffffffL),   // lw x4,0x100(x0)
        BigInt(5)  -> BigInt(b(0, 3, 4, 8) & 0xffffffffL),             // beq x3,x4,+8
        BigInt(6)  -> BigInt(i(0x13, 5, 0, 0, 1) & 0xffffffffL),       // skipped
        BigInt(7)  -> BigInt(i(0x13, 6, 1, 4, 2) & 0xffffffffL),       // slli x6,x4,2
        BigInt(8)  -> BigInt(i(0x73, 7, 1, 6, 0x340) & 0xffffffffL),   // csrrw x7,mscratch,x6
        BigInt(9)  -> BigInt(i(0x73, 8, 2, 0, 0x340) & 0xffffffffL),   // csrrs x8,mscratch,x0
        BigInt(10) -> BigInt(r(0x3b, 9, 1, 6, 1) & 0xffffffffL),       // sllw x9,x6,x1
        BigInt(11) -> BigInt(r(0x3b, 10, 5, 9, 1) & 0xffffffffL),      // srlw x10,x9,x1
        BigInt(12) -> BigInt(0x0000100fL),                             // fence.i
        BigInt(13) -> BigInt(0x0000006fL)                              // jal x0,0
      )

      dut.io.wb.ack.poke(false.B)
      dut.io.wb.err.poke(false.B)
      dut.io.wb.dat_r.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)

      val retired = mutable.ArrayBuffer.empty[(BigInt, Int, BigInt)]
      var cycles = 0
      while (retired.size < 13 && cycles < 2400) {
        val active = dut.io.wb.cyc.peek().litToBoolean && dut.io.wb.stb.peek().litToBoolean
        val address = dut.io.wb.adr.peekValue().asBigInt
        dut.io.wb.ack.poke(active.B)
        dut.io.wb.err.poke(false.B)
        dut.io.wb.dat_r.poke(words.getOrElse(address, BigInt(0)).U)
        if (active && dut.io.wb.we.peek().litToBoolean) {
          val old = words.getOrElse(address, BigInt(0))
          val data = dut.io.wb.dat_w.peekValue().asBigInt
          val sel = dut.io.wb.sel.peekValue().asBigInt
          var merged = old
          for (lane <- 0 until 4 if ((sel >> lane) & 1) != 0) {
            val mask = BigInt(0xff) << (8 * lane)
            merged = (merged & ~mask) | (data & mask)
          }
          words(address) = merged & BigInt("ffffffff", 16)
        }
        dut.clock.step(1)
        if (dut.io.trace.valid.peek().litToBoolean) {
          assert(!dut.io.trace.trap.peek().litToBoolean,
            s"unexpected trap cause=${dut.io.trace.cause.peekValue()}")
          retired += ((dut.io.trace.pc.peekValue().asBigInt,
            dut.io.trace.rd.peekValue().asBigInt.toInt,
            dut.io.trace.rdValue.peekValue().asBigInt))
        }
        cycles += 1
      }

      assert(retired.size == 13, s"only retired ${retired.size} instructions in $cycles cycles: $retired")
      assert(retired.map(_._1) == Seq(0, 4, 8, 12, 16, 20, 28, 32, 36, 40, 44, 48, 52))
      assert(retired.find(_._2 == 3).exists(_._3 == 12))
      assert(retired.find(_._2 == 4).exists(_._3 == 12))
      assert(retired.find(_._2 == 6).exists(_._3 == 48))
      assert(retired.find(_._2 == 7).exists(_._3 == 0))
      assert(retired.find(_._1 == 36).exists(_._3 == 48), s"unexpected retire values: $retired")
      assert(retired.find(_._2 == 9).exists(_._3 == 1536), s"SLLW used the wrong rs2 byte: $retired")
      assert(retired.find(_._2 == 10).exists(_._3 == 48), s"SRLW used the wrong rs2 byte: $retired")
      assert(words(BigInt(0x100 / 4)) == 12)
    }
  }

  "boot the EP4CE10 ROM firmware and write WISP over the UART MMIO window" in {
    val image = Files.readAllBytes(Paths.get("..", "software", "wisp-ep4ce10",
      "build", "wisp-ep4ce10.bin"))
    val romWords = image.grouped(4).zipWithIndex.map { case (bytes, index) =>
      val padded = bytes.padTo(4, 0.toByte)
      val word = padded.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (byte, lane)) =>
        acc | (BigInt(byte & 0xff) << (8 * lane))
      }
      (BigInt("10000000", 16) / 4 + index) -> word
    }.toMap
    val uartRxtx = BigInt("12001000", 16) / 4
    val uartTxFull = BigInt("12001004", 16) / 4

    simulate(new WispCore(resetVector = BigInt("10000000", 16), withTrace = true)) { dut =>
      dut.io.timerIrq.poke(false.B); dut.io.externalIrq.poke(false.B)
      dut.io.wb.ack.poke(false.B); dut.io.wb.err.poke(false.B); dut.io.wb.dat_r.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      val uart = mutable.ArrayBuffer.empty[Char]
      var cycles = 0
      while (uart.size < 6 && cycles < 180000) {
        val active = dut.io.wb.cyc.peek().litToBoolean && dut.io.wb.stb.peek().litToBoolean
        val address = dut.io.wb.adr.peekValue().asBigInt
        dut.io.wb.ack.poke(active.B)
        dut.io.wb.err.poke(false.B)
        dut.io.wb.dat_r.poke((if (address == uartTxFull) BigInt(0)
          else romWords.getOrElse(address, BigInt(0))).U)
        if (active && dut.io.wb.we.peek().litToBoolean && address == uartRxtx) {
          uart += (dut.io.wb.dat_w.peekValue().asBigInt & 0xff).toInt.toChar
        }
        dut.clock.step(1)
        if (dut.io.trace.valid.peek().litToBoolean) {
          assert(!dut.io.trace.trap.peek().litToBoolean,
            s"firmware trapped at pc=0x${dut.io.trace.pc.peekValue().asBigInt.toString(16)}")
        }
        cycles += 1
      }
      assert(uart.mkString == "WISP\r\n",
        s"UART output '${uart.mkString}' after $cycles cycles")
    }
  }
}

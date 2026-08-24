package flow.air

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable
import java.nio.file.{Files, Paths}

class AirCoreSpec extends AnyFreeSpec with ChiselSim {
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

  private def run(core: => AirCore, words: mutable.Map[BigInt, BigInt], retireCount: Int,
      maxCycles: Int = 3000): Seq[(BigInt, Int, BigInt, Int, BigInt)] = {
    var retired = Seq.empty[(BigInt, Int, BigInt, Int, BigInt)]
    simulate(core) { dut =>
      dut.io.timerIrq.poke(false.B); dut.io.externalIrq.poke(false.B)
      dut.io.wb.ack.poke(false.B); dut.io.wb.err.poke(false.B); dut.io.wb.dat_r.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      val events = mutable.ArrayBuffer.empty[(BigInt, Int, BigInt, Int, BigInt)]
      var cycles = 0
      while (events.size < retireCount && cycles < maxCycles) {
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
            s"unexpected trap cause=${dut.io.trace.cause.peekValue()} pc=0x${dut.io.trace.pc.peekValue().asBigInt.toString(16)} " +
              s"insn=0x${dut.io.trace.instruction.peekValue().asBigInt.toString(16)}")
          events += ((dut.io.trace.pc.peekValue().asBigInt,
            dut.io.trace.rd.peekValue().asBigInt.toInt,
            dut.io.trace.rdValue.peekValue().asBigInt,
            dut.io.trace.instructionBytes.peekValue().asBigInt.toInt,
            dut.io.trace.instruction.peekValue().asBigInt))
        }
        cycles += 1
      }
      assert(events.size == retireCount, s"retired ${events.size}/$retireCount in $cycles cycles: $events")
      retired = events.toSeq
    }
    retired
  }

  "Air-I executes ALU, pipelined two-source RF, memory and branch paths" in {
    val words = mutable.Map[BigInt, BigInt](
      BigInt(0) -> BigInt(i(0x13, 1, 0, 0, 5) & 0xffffffffL),
      BigInt(1) -> BigInt(i(0x13, 2, 0, 0, 7) & 0xffffffffL),
      BigInt(2) -> BigInt(r(0x33, 3, 0, 1, 2) & 0xffffffffL),
      BigInt(3) -> BigInt(s(0x23, 2, 0, 3, 0x100) & 0xffffffffL),
      BigInt(4) -> BigInt(i(0x03, 4, 2, 0, 0x100) & 0xffffffffL),
      BigInt(5) -> BigInt(b(0, 3, 4, 8) & 0xffffffffL),
      BigInt(6) -> BigInt(i(0x13, 5, 0, 0, 1) & 0xffffffffL),
      BigInt(7) -> BigInt(i(0x13, 6, 0, 4, 1) & 0xffffffffL),
      BigInt(8) -> BigInt(0x0000006fL))
    val retired = run(new AirCore(withCompressed = false, withTrace = true), words, 8)
    assert(retired.map(_._1) == Seq(0, 4, 8, 12, 16, 20, 28, 32))
    assert(retired.find(_._2 == 3).exists(_._3 == 12))
    assert(retired.find(_._2 == 4).exists(_._3 == 12))
    assert(retired.find(_._2 == 6).exists(_._3 == 13))
    assert(words(BigInt(0x100 / 4)) == 12)
    assert(retired.forall(_._4 == 4))
  }

  "Air-IC executes compressed instructions and a 32-bit instruction crossing a word" in {
    def cLi(rd: Int, imm: Int): Int =
      (2 << 13) | (((imm >> 5) & 1) << 12) | (rd << 7) | ((imm & 0x1f) << 2) | 1
    def cAdd(rd: Int, rs2: Int): Int =
      (4 << 13) | (1 << 12) | (rd << 7) | (rs2 << 2) | 2
    def cAddi(rd: Int, imm: Int): Int =
      (((imm >> 5) & 1) << 12) | (rd << 7) | ((imm & 0x1f) << 2) | 1
    val addiX2 = i(0x13, 2, 0, 0, 7)
    val word0 = BigInt(cLi(1, 5)) | (BigInt(addiX2 & 0xffff) << 16)
    val word1 = BigInt((addiX2 >>> 16) & 0xffff) | (BigInt(cAdd(1, 2)) << 16)
    val word2 = BigInt(cAddi(1, 1)) | (BigInt(0xa001L) << 16) // C.J 0
    val words = mutable.Map[BigInt, BigInt](BigInt(0) -> word0, BigInt(1) -> word1,
      BigInt(2) -> word2)

    val retired = run(new AirCore(withCompressed = true, withTrace = true), words, 5)
    assert(retired.map(_._1) == Seq(0, 2, 6, 8, 10))
    assert(retired.map(_._4) == Seq(2, 4, 2, 2, 2))
    assert(retired.find(_._1 == 6).exists(event => event._2 == 1 && event._3 == 12),
      s"unexpected compressed ADD trace: $retired")
    assert(retired.find(_._1 == 8).exists(event => event._2 == 1 && event._3 == 13),
      s"unexpected compressed ADDI trace: $retired")
  }

  for ((name, compressed, variant) <- Seq(
      ("Air-I", false, "i"), ("Air-IC", true, "ic"))) {
    s"$name boots its independently compiled UART firmware" in {
      val image = Files.readAllBytes(Paths.get("..", "software", "air-ep4ce10",
        "build", variant, "air-ep4ce10.bin"))
      val romBase = BigInt("10000000", 16) / 4
      val words = mutable.Map.from(image.grouped(4).zipWithIndex.map { case (bytes, index) =>
        val padded = bytes.padTo(4, 0.toByte)
        val word = padded.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (byte, lane)) =>
          acc | (BigInt(byte & 0xff) << (8 * lane))
        }
        (romBase + index) -> word
      })
      val uartRxtx = BigInt("12001000", 16) / 4
      val uartTxFull = BigInt("12001004", 16) / 4

      simulate(new AirCore(resetVector = BigInt("10000000", 16),
          withCompressed = compressed, withTrace = true)) { dut =>
        dut.io.timerIrq.poke(false.B); dut.io.externalIrq.poke(false.B)
        dut.io.wb.ack.poke(false.B); dut.io.wb.err.poke(false.B); dut.io.wb.dat_r.poke(0.U)
        dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
        val uart = mutable.ArrayBuffer.empty[Char]
        var cycles = 0
        while (uart.size < 5 && cycles < 160000) {
          val active = dut.io.wb.cyc.peek().litToBoolean && dut.io.wb.stb.peek().litToBoolean
          val address = dut.io.wb.adr.peekValue().asBigInt
          dut.io.wb.ack.poke(active.B)
          dut.io.wb.err.poke(false.B)
          dut.io.wb.dat_r.poke((if (address == uartTxFull) BigInt(0)
            else words.getOrElse(address, BigInt(0))).U)
          if (active && dut.io.wb.we.peek().litToBoolean && address == uartRxtx) {
            uart += (dut.io.wb.dat_w.peekValue().asBigInt & 0xff).toInt.toChar
          }
          dut.clock.step()
          if (dut.io.trace.valid.peek().litToBoolean) {
            assert(!dut.io.trace.trap.peek().litToBoolean,
              s"$name firmware trapped at pc=0x${dut.io.trace.pc.peekValue().asBigInt.toString(16)} " +
                s"cause=${dut.io.trace.cause.peekValue()}")
          }
          cycles += 1
        }
        assert(uart.mkString == "AIR\r\n", s"$name UART '${uart.mkString}' after $cycles cycles")
      }
    }
  }
}

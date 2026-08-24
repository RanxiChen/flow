package flow.mcu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.air.AirCore
import flow.wisp.WispCore
import org.scalatest.freespec.AnyFreeSpec

import java.nio.file.{Files, Paths}
import scala.collection.mutable

class McuFirmwareSpec extends AnyFreeSpec with ChiselSim {
  private val romBase = BigInt("10000000", 16) / 4
  private val uartRxtx = BigInt("12001000", 16) / 4
  private val uartTxFull = BigInt("12001004", 16) / 4
  private val timerEnable = BigInt("12002008", 16) / 4
  private val timerPending = BigInt("12002018", 16) / 4
  private val gpioOut = BigInt("12004000", 16) / 4
  private val seg7Digits = BigInt("12005000", 16) / 4
  private val seg7Enable = BigInt("12005004", 16) / 4

  private def image(core: String): mutable.Map[BigInt, BigInt] = {
    val bytes = Files.readAllBytes(Paths.get("..", "software", "ep4ce10-mcu",
      "build", core, "led-timer-irq.bin"))
    mutable.Map.from(bytes.grouped(4).zipWithIndex.map { case (group, index) =>
      val word = group.padTo(4, 0.toByte).zipWithIndex.foldLeft(BigInt(0)) {
        case (acc, (byte, lane)) => acc | (BigInt(byte & 0xff) << (8 * lane))
      }
      (romBase + index) -> word
    })
  }

  private def mergeWrite(memory: mutable.Map[BigInt, BigInt], address: BigInt,
      data: BigInt, select: BigInt): Unit = {
    var merged = memory.getOrElse(address, BigInt(0))
    for (lane <- 0 until 4 if ((select >> lane) & 1) != 0) {
      val mask = BigInt(0xff) << (8 * lane)
      merged = (merged & ~mask) | (data & mask)
    }
    memory(address) = merged & BigInt("ffffffff", 16)
  }

  "Air-IC boots the shared BSP, sleeps, handles MTIP and writes the LED GPIO" in {
    val memory = image("air-ic")
    simulate(new AirCore(resetVector = BigInt("10000000", 16),
        withCompressed = true, withTrace = true)) { dut =>
      dut.io.timerIrq.poke(false.B); dut.io.externalIrq.poke(false.B)
      dut.io.wb.ack.poke(false.B); dut.io.wb.err.poke(false.B); dut.io.wb.dat_r.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      val uart = mutable.ArrayBuffer.empty[Char]
      var timerArmed = false
      var quiet = 0
      var irqRaised = false
      var ledOne = false
      var seg7One = false
      var seg7Enabled = false
      var cycles = 0
      while (!(ledOne && seg7One && seg7Enabled) && cycles < 300000) {
        val active = dut.io.wb.cyc.peek().litToBoolean && dut.io.wb.stb.peek().litToBoolean
        val address = dut.io.wb.adr.peekValue().asBigInt
        val write = active && dut.io.wb.we.peek().litToBoolean
        val data = dut.io.wb.dat_w.peekValue().asBigInt
        dut.io.wb.ack.poke(active.B)
        dut.io.wb.dat_r.poke((if (address == uartTxFull) BigInt(0)
          else memory.getOrElse(address, BigInt(0))).U)
        if (write) {
          mergeWrite(memory, address, data, dut.io.wb.sel.peekValue().asBigInt)
          if (address == uartRxtx) uart += (data & 0xff).toInt.toChar
          if (address == timerEnable && (data & 1) == 1) timerArmed = true
          if (address == timerPending && irqRaised) dut.io.timerIrq.poke(false.B)
          if (address == gpioOut && (data & 1) == 1) ledOne = true
          if (address == seg7Digits && (data & 0xffffff) == 1) seg7One = true
          if (address == seg7Enable && (data & 0x3f) == 0x3f) seg7Enabled = true
        }
        if (timerArmed && !active) quiet += 1 else if (active) quiet = 0
        if (!irqRaised && quiet >= 3) {
          irqRaised = true
          dut.io.timerIrq.poke(true.B)
        }
        dut.clock.step()
        cycles += 1
      }
      assert(uart.mkString == "MCU IRQ READY\r\n", s"Air UART was '${uart.mkString}'")
      assert(timerArmed && irqRaised && ledOne && seg7One && seg7Enabled,
        s"Air MCU flow incomplete after $cycles cycles")
    }
  }

  "Wisp handles ten firmware MTIPs and carries the packed-BCD display to 000010" in {
    val memory = image("wisp")
    simulate(new WispCore(resetVector = BigInt("10000000", 16), withTrace = true)) { dut =>
      dut.io.timerIrq.poke(false.B); dut.io.externalIrq.poke(false.B)
      dut.io.wb.ack.poke(false.B); dut.io.wb.err.poke(false.B); dut.io.wb.dat_r.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      val uart = mutable.ArrayBuffer.empty[Char]
      var timerArmed = false
      var quiet = 0
      var irqLevel = false
      var irqCount = 0
      var ledOne = false
      var seg7Ten = false
      var seg7Enabled = false
      var cycles = 0
      while (!(ledOne && seg7Ten && seg7Enabled) && cycles < 1500000) {
        val active = dut.io.wb.cyc.peek().litToBoolean && dut.io.wb.stb.peek().litToBoolean
        val address = dut.io.wb.adr.peekValue().asBigInt
        val write = active && dut.io.wb.we.peek().litToBoolean
        val data = dut.io.wb.dat_w.peekValue().asBigInt
        dut.io.wb.ack.poke(active.B)
        dut.io.wb.dat_r.poke((if (address == uartTxFull) BigInt(0)
          else memory.getOrElse(address, BigInt(0))).U)
        if (write) {
          mergeWrite(memory, address, data, dut.io.wb.sel.peekValue().asBigInt)
          if (address == uartRxtx) uart += (data & 0xff).toInt.toChar
          if (address == timerEnable && (data & 1) == 1) timerArmed = true
          if (address == timerPending && irqLevel) {
            dut.io.timerIrq.poke(false.B)
            irqLevel = false
            irqCount += 1
            quiet = 0
          }
          if (address == gpioOut && (data & 1) == 1) ledOne = true
          if (address == seg7Digits && (data & 0xffffff) == 0x10) seg7Ten = true
          if (address == seg7Enable && (data & 0x3f) == 0x3f) seg7Enabled = true
        }
        if (timerArmed && !irqLevel && !active) quiet += 1 else if (active) quiet = 0
        if (!irqLevel && irqCount < 10 && quiet >= 3) {
          irqLevel = true
          dut.io.timerIrq.poke(true.B)
        }
        dut.clock.step()
        cycles += 1
      }
      assert(uart.mkString == "MCU IRQ READY\r\n", s"Wisp UART was '${uart.mkString}'")
      assert(timerArmed && irqCount == 10 && ledOne && seg7Ten && seg7Enabled,
        s"Wisp MCU decimal carry incomplete after $irqCount IRQs and $cycles cycles")
    }
  }
}

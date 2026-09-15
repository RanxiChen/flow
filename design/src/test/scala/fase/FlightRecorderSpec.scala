package flow.fase

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class FlightRecorderSpec extends AnyFreeSpec with ChiselSim {
  "banked history wraps, freezes on filtered faults, and survives host backpressure" in {
    simulate(new FlightRecorder(4)) { d =>
      val h = d.io.host
      d.io.privilege.poke(1.U)
      d.io.events.foreach { e => e.valid.poke(false.B); e.words.foreach(_.poke(0.U)) }
      h.cmd.valid.poke(false.B); h.rsp.ready.poke(false.B)
      h.cmd.bits.opcode.poke(0.U); h.cmd.bits.index.poke(0.U)
      h.cmd.bits.pc.poke(0.U); h.cmd.bits.data.poke(0.U)
      d.clock.step(2)
      def cmd(op: Int, data: BigInt = 0, index: Int = 0, error: Boolean = false): BigInt = {
        var n = 0
        while (!h.cmd.ready.peek().litToBoolean && n < 20) { d.clock.step(); n += 1 }
        assert(h.cmd.ready.peek().litToBoolean)
        h.cmd.bits.opcode.poke(op.U); h.cmd.bits.data.poke(data.U); h.cmd.bits.index.poke(index.U)
        h.cmd.valid.poke(true.B); d.clock.step(); h.cmd.valid.poke(false.B)
        n = 0
        while (!h.rsp.valid.peek().litToBoolean && n < 20) { d.clock.step(); n += 1 }
        h.rsp.valid.expect(true.B); h.rsp.bits.error.expect(error.B)
        val value = h.rsp.bits.data.peek().litValue
        d.clock.step(2) // response must remain intact until accepted
        h.rsp.valid.expect(true.B); h.rsp.bits.data.expect(value.U)
        h.rsp.ready.poke(true.B); d.clock.step(); h.rsp.ready.poke(false.B)
        value
      }
      def read(bank: Int, slot: Int, word: Int): BigInt = cmd(19, (bank << 13) | (slot << 3) | word)
      assert((cmd(16) & 7) == 2) // disarmed after reset
      cmd(17, 1 | (15 << 8))
      for (i <- 0 until 6) {
        for (b <- 0 until 6) {
          d.io.events(b).valid.poke(true.B)
          d.io.events(b).words(1).poke((100*b+i).U)
        }
        d.clock.step()
      }
      d.io.events.foreach(_.valid.poke(false.B)); d.clock.step()
      cmd(19, error = true) // running memory is not readable
      cmd(17, 3, error = true) // invalid control cannot clear or freeze
      assert((cmd(16) & 1) == 1)
      cmd(17, 2)
      for (b <- 0 until 6) {
        val s = cmd(16, index = b+1)
        assert((s >> 32) == 6 && ((s >> 16) & 65535) == 4 && (s & 65535) == 2)
        assert(read(b, 2, 2) == 100*b+2)
        assert(read(b, 1, 2) == 100*b+5)
        assert((read(b, 1, 1) >> 62) == 1)
      }
      cmd(19, 6 << 13, error = true)
      cmd(19, 4 << 3, error = true)
      cmd(17, 1 | 8 | (2 << 8)) // supervisor page fault, zero post cycles
      val e = d.io.events(5)
      e.valid.poke(true.B); e.words(0).poke(6.U) // U fault excluded
      d.clock.step(2); e.valid.poke(false.B)
      assert((cmd(16) & 7) == 1)
      e.valid.poke(true.B); e.words(0).poke(262.U); e.words(2).poke("hffffffff88ba597e".U)
      d.clock.step(); e.valid.poke(false.B); d.clock.step()
      assert((cmd(16) & 7) == 6)
      val triggerSlot = cmd(16, index = 13).toInt
      assert(read(5, triggerSlot, 3) == BigInt("ffffffff88ba597e", 16))
      assert(read(5, triggerSlot, 0) == cmd(16, index = 7))
      cmd(17, 4)
      cmd(19, error = true) // clear invalidates old RAM without resetting RAM contents
      cmd(18, 0x12340000); cmd(20, BigInt("ffffffffffff0000", 16))
      cmd(17, 1 | 16 | (2 << 8) | (2 << 16))
      e.valid.poke(true.B); e.words(0).poke(257.U); e.words(1).poke(0x12345678.U)
      d.clock.step(); e.valid.poke(false.B); d.clock.step()
      assert((d.io.probes(1).peek().litValue & 4) == 0) // post-trigger recording still active
      d.clock.step(2)
      assert((cmd(16) & 7) == 6)
    }
  }
}

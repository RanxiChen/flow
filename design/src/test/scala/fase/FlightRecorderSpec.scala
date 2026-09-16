package flow.fase

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable

class FlightRecorderSpec extends AnyFreeSpec with ChiselSim {
  "reset-on history retains overlapping M/S/U faults through wrap, eviction and leased reads" in {
    // Same geometry invariant as hardware, with small pages to force reuse.
    simulate(new FlightRecorder(depth = 256, pageSize = 4, preEntries = 8, postCycles = 4)) { d =>
      val h = d.io.host
      h.cmd.valid.poke(false.B); h.rsp.ready.poke(false.B)
      h.cmd.bits.opcode.poke(0.U); h.cmd.bits.index.poke(0.U)
      h.cmd.bits.pc.poke(0.U); h.cmd.bits.data.poke(0.U)
      d.io.privilege.poke(3.U)
      d.io.events.foreach { e => e.valid.poke(false.B); e.words.foreach(_.poke(0.U)) }
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      var stepId = 0
      var autoId = 0
      val expected = mutable.Map.empty[Int, (Int, Int)]
      def tick(n: Int = 1, faultPriv: Option[Int] = None, irq: Boolean = false, speculative: Boolean = false): Unit = {
        for (_ <- 0 until n) {
          val priv = faultPriv.getOrElse(3)
          d.io.privilege.poke(priv.U)
          for (b <- 0 until 6) {
            val e = d.io.events(b)
            e.valid.poke(true.B)
            for (w <- 0 until 7) e.words(w).poke((stepId * 100 + b * 10 + w).U)
            if (b == 0) {
              e.words(0).poke(((priv << 16) | 1 | (if (irq) 2 else 0)).U)
              e.words(3).poke((if (faultPriv.nonEmpty) 12 else 8).U)
            }
            if (b == 5) e.words(0).poke((if (speculative) 0x306 else 0x301).U)
          }
          if (faultPriv.nonEmpty && !irq) {
            autoId += 1; expected(autoId) = (stepId, priv)
          }
          d.clock.step(); stepId += 1
        }
      }
      def cmd(op: Int, data: BigInt = 0, index: Int = 0, error: Boolean = false): BigInt = {
        var n = 0
        while (!h.cmd.ready.peek().litToBoolean && n < 20) { tick(); n += 1 }
        assert(h.cmd.ready.peek().litToBoolean)
        h.cmd.bits.opcode.poke(op.U); h.cmd.bits.data.poke(data.U); h.cmd.bits.index.poke(index.U)
        h.cmd.valid.poke(true.B); tick(); h.cmd.valid.poke(false.B)
        n = 0
        while (!h.rsp.valid.peek().litToBoolean && n < 20) { tick(); n += 1 }
        h.rsp.valid.expect(true.B); h.rsp.bits.error.expect(error.B)
        val value = h.rsp.bits.data.peek().litValue
        tick(2) // trace writes must continue under host response backpressure
        h.rsp.valid.expect(true.B); h.rsp.bits.data.expect(value.U)
        h.rsp.ready.poke(true.B); tick(); h.rsp.ready.poke(false.B)
        value
      }
      def slots(): Map[Int, Int] = (0 until 8).flatMap { s =>
        if ((cmd(17, s) & 1) == 1) Some(cmd(17, s, 1).toInt -> s) else None
      }.toMap
      def read(b: Int, entry: Int, word: Int): BigInt = cmd(19, (b << 13) | (entry << 3) | word)
      def check(id: Int): Unit = {
        val (source, priv) = expected(id)
        cmd(18, id)
        val s = slots()(id)
        assert((cmd(17, s) & 7) == 7)
        assert((cmd(17, s, 3) >> 16 & 3) == priv)
        assert(cmd(17, s, 4) == source * 100 + 1) // fault PC
        assert(cmd(17, s, 6) == 12)
        for (b <- 0 until 6) {
          val sizes = cmd(17, s, 10+b)
          assert((sizes & 65535) == 8 && (sizes >> 16) == 13)
          for (i <- 0 until 13) {
            assert(read(b, i, 2) == (source - 8 + i) * 100 + b * 10 + 1,
              s"id=$id bank=$b entry=$i did not retain exact source history")
          }
          cmd(19, (b << 13) | (13 << 3), error = true)
        }
      }
      tick(20)
      assert((cmd(16) & 1) == 1)
      assert(cmd(16, index = 15) == 2)
      assert(cmd(16, index = 1) > 0) // no host ARM was sent
      cmd(19, error = true) // no selected snapshot
      tick(3, speculative = true); tick(1, faultPriv = Some(3), irq = true)
      assert(cmd(16, index = 7) == 0) // wrong-path MMU faults and interrupts excluded
      tick(faultPriv = Some(0)); tick(8)
      check(1) // U-mode real instruction fault is retained
      val stable = read(3, 0, 2)
      // More than one complete physical-store wrap while preserving the lease.
      tick(700)
      for (i <- 0 until 16) tick(faultPriv = Some(Seq(0, 1, 3)(i % 3)))
      tick(8)
      assert(cmd(16, index = 7) == 17)
      assert(cmd(16, index = 8) == 9)
      assert(read(3, 0, 2) == stable)
      assert(slots().keySet == Set(1, 11, 12, 13, 14, 15, 16, 17))
      cmd(18, 2, error = true)
      assert(cmd(16, index = 10) == 1) // failed select cannot release old lease
      cmd(20)
      // Dense snapshots overlap in the same physical pages, including rollover.
      for (id <- 11 to 17) check(id)
      assert(cmd(16, index = 14) == 0)
      cmd(19, 6 << 13, error = true)
      cmd(22, error = true)
      // Manual capture leases its slot immediately and never stops recording.
      val manualId = cmd(21).toInt
      tick(8)
      assert(cmd(16, index = 10) == manualId)
      val ms = slots()(manualId)
      assert((cmd(17, ms) & 15) == 15)
      val mvalue = read(3, 0, 2)
      tick(500)
      assert(read(3, 0, 2) == mvalue)
      cmd(23)
      assert(cmd(16, index = 7) == 0 && cmd(16, index = 10) == 0)
      assert(cmd(16, index = 1) > 0) // CLEAR also leaves automatic recording on
      cmd(19, error = true)
      // Hardware reset, after a prior capture/lease, must restart without ARM.
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      // A fault on the very first source cycle has no preceding history.
      tick(faultPriv = Some(3)); tick(8)
      assert(cmd(16, index = 7) == 1 && cmd(16, index = 1) > 0)
      cmd(18, 1)
      val earlySlot = slots()(1)
      for (b <- 0 until 6) {
        val sizes = cmd(17, earlySlot, 10+b)
        assert((sizes & 65535) == 0 && (sizes >> 16) == 5)
      }
      assert(cmd(16, index = 14) == 0)
    }
  }
}

package flow.accelerator.matrix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class MatrixEngineSpec extends AnyFreeSpec with ChiselSim {
  private val aBaseWord = 0x000
  private val bBaseWord = 0x400
  private val cBaseWord = 0x800

  private def packInt8(values: Seq[Int]): BigInt =
    values.zipWithIndex.foldLeft(BigInt(0)) { case (word, (value, lane)) =>
      word | (BigInt(value & 0xff) << (8 * lane))
    }

  private def signed32(value: BigInt): BigInt = {
    if (value < 0) value
    else if ((value & (BigInt(1) << 31)) != 0) value - (BigInt(1) << 32)
    else value
  }

  "execute one asynchronous tail tile from non-zero SPM bases" in {
    simulate(new MatrixEngine(spmDepth = 16)) { dut =>
      dut.io.start.poke(false.B)
      dut.io.m.poke(0.U); dut.io.n.poke(0.U); dut.io.k.poke(0.U)
      dut.io.aBase.poke(0.U); dut.io.bBase.poke(0.U); dut.io.cBase.poke(0.U)
      dut.io.spm.cyc.poke(false.B); dut.io.spm.stb.poke(false.B)
      dut.io.spm.we.poke(false.B); dut.io.spm.adr.poke(0.U)
      dut.io.spm.dat_w.poke(0.U); dut.io.spm.sel.poke(0.U)
      dut.io.spm.cti.poke(0.U); dut.io.spm.bte.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)

      def wbWrite(address: Int, data: BigInt): Unit = {
        dut.io.spm.adr.poke(address.U)
        dut.io.spm.dat_w.poke(data.U)
        dut.io.spm.sel.poke("b1111".U)
        dut.io.spm.we.poke(true.B)
        dut.io.spm.cyc.poke(true.B); dut.io.spm.stb.poke(true.B)
        var cycles = 0
        while (!dut.io.spm.ack.peek().litToBoolean && cycles < 5) {
          dut.clock.step(); cycles += 1
        }
        assert(dut.io.spm.ack.peek().litToBoolean)
        dut.io.spm.cyc.poke(false.B); dut.io.spm.stb.poke(false.B)
        dut.io.spm.we.poke(false.B); dut.clock.step()
      }

      def wbRead(address: Int): BigInt = {
        dut.io.spm.adr.poke(address.U)
        dut.io.spm.sel.poke("b1111".U)
        dut.io.spm.we.poke(false.B)
        dut.io.spm.cyc.poke(true.B); dut.io.spm.stb.poke(true.B)
        var cycles = 0
        while (!dut.io.spm.ack.peek().litToBoolean && cycles < 5) {
          dut.clock.step(); cycles += 1
        }
        assert(dut.io.spm.ack.peek().litToBoolean)
        val value = dut.io.spm.dat_r.peekValue().asBigInt
        dut.io.spm.cyc.poke(false.B); dut.io.spm.stb.poke(false.B)
        dut.clock.step()
        value
      }

      val a = Seq(
        Seq(1, -2, 3, 4, -1),
        Seq(-3, 1, 2, -2, 5),
        Seq(4, 0, -1, 3, 2))
      val b = Seq(
        Seq(2, -1),
        Seq(-2, 4),
        Seq(1, 2),
        Seq(3, -1),
        Seq(-1, 3))
      val initialC = Seq(Seq(11, -7), Seq(-19, 23), Seq(5, -13))
      val expected = Seq.tabulate(3, 2) { case (row, col) =>
        initialC(row)(col) + (0 until 5).map(k => a(row)(k) * b(k)(col)).sum
      }
      val aBase = 3
      val bBase = 4
      val cBase = 2

      for (k <- 0 until 5) {
        wbWrite(aBaseWord + aBase + k,
          packInt8(Seq(a(0)(k), a(1)(k), a(2)(k), 0)))
        wbWrite(bBaseWord + bBase + k,
          packInt8(Seq(b(k)(0), b(k)(1), 0, 0)))
      }
      for (row <- 0 until 3; col <- 0 until 2) {
        wbWrite(cBaseWord + 4 * (cBase + row) + col,
          BigInt(initialC(row)(col) & 0xffffffffL))
      }

      dut.io.m.poke(3.U); dut.io.n.poke(2.U); dut.io.k.poke(5.U)
      dut.io.aBase.poke(aBase.U); dut.io.bBase.poke(bBase.U); dut.io.cBase.poke(cBase.U)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
      assert(dut.io.busy.peek().litToBoolean)
      assert(!dut.io.done.peek().litToBoolean)

      var workCycles = 0
      while (dut.io.busy.peek().litToBoolean && workCycles < 100) {
        dut.clock.step(); workCycles += 1
      }
      assert(workCycles > 1 && workCycles < 100)
      assert(dut.io.done.peek().litToBoolean)
      assert(!dut.io.error.peek().litToBoolean)

      for (row <- 0 until 3; col <- 0 until 2) {
        val address = cBaseWord + 4 * (cBase + row) + col
        val actual = signed32(wbRead(address))
        assert(actual == expected(row)(col),
          s"C($row,$col) expected ${expected(row)(col)}, got $actual")
      }
    }
  }

  "reject illegal shape and a second start while busy" in {
    simulate(new MatrixEngine(spmDepth = 16)) { dut =>
      dut.io.spm.cyc.poke(false.B); dut.io.spm.stb.poke(false.B)
      dut.io.spm.we.poke(false.B); dut.io.spm.adr.poke(0.U)
      dut.io.spm.dat_w.poke(0.U); dut.io.spm.sel.poke(0.U)
      dut.io.spm.cti.poke(0.U); dut.io.spm.bte.poke(0.U)
      dut.io.aBase.poke(0.U); dut.io.bBase.poke(0.U); dut.io.cBase.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)

      dut.io.m.poke(5.U); dut.io.n.poke(1.U); dut.io.k.poke(1.U)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
      assert(!dut.io.busy.peek().litToBoolean)
      assert(dut.io.error.peek().litToBoolean)

      dut.io.m.poke(1.U); dut.io.n.poke(1.U); dut.io.k.poke(1.U)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
      assert(dut.io.busy.peek().litToBoolean)

      // The same SPM window is ordinary CPU data RAM while idle, but the
      // single-buffer engine owns it exclusively until this command finishes.
      dut.io.spm.adr.poke(cBaseWord.U)
      dut.io.spm.we.poke(false.B)
      dut.io.spm.cyc.poke(true.B); dut.io.spm.stb.poke(true.B)
      var busCycles = 0
      while (!dut.io.spm.err.peek().litToBoolean && busCycles < 5) {
        dut.clock.step(); busCycles += 1
      }
      assert(dut.io.spm.err.peek().litToBoolean)
      assert(!dut.io.spm.ack.peek().litToBoolean)
      dut.io.spm.cyc.poke(false.B); dut.io.spm.stb.poke(false.B)
      dut.clock.step()

      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
      assert(dut.io.error.peek().litToBoolean)
    }
  }
}

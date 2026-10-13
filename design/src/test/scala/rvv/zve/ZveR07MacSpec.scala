package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable
import scala.util.Random

/** Byte-addressed model independent of the MAC pipeline and its row slicing. */
class ZveR07MacSpec extends AnyFreeSpec with ChiselSim {
  private val p = ZveParams.soc(32, 32)
  private val ops = Seq(ZveOp.vwmul, ZveOp.vwmulu, ZveOp.vwmulsu,
    ZveOp.vwmacc, ZveOp.vwmaccu, ZveOp.vwmaccsu, ZveOp.vwmaccus)

  "R07 MAC matches element model under return and write backpressure" in {
    simulate(new ZveMacVectorEngine(p)) { dut =>
      val io = dut.io
      io.in.valid.poke(false.B); io.complete.ready.poke(true.B)
      io.vrf.read.ready.poke(false.B); io.vrf.returned.valid.poke(false.B)
      io.vrf.write.ready.poke(false.B); io.vrf.written.valid.poke(false.B)
      io.mask.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
      val rng = new Random(0x707)
      val rows = Array.fill(p.vrf.rows, p.vrf.rowBytes)(0)
      val full64 = (BigInt(1) << 64) - 1
      def pack(row: Array[Int]): BigInt = row.zipWithIndex.foldLeft(BigInt(0)) {
        case (v, (b, j)) => v | (BigInt(b) << (8 * j))
      }
      def element(base: Int, index: Int, width: Int): BigInt = {
        val byte = base * p.vrf.rowsPerReg * p.vrf.rowBytes + index * (width / 8)
        (0 until width / 8).foldLeft(BigInt(0)) { (v, j) =>
          val at = byte + j
          v | (BigInt(rows(at / p.vrf.rowBytes)(at % p.vrf.rowBytes)) << (8 * j))
        }
      }
      def signed(v: BigInt, width: Int): BigInt =
        if (v.testBit(width - 1)) v - (BigInt(1) << width) else v
      def put(base: Int, index: Int, width: Int, value: BigInt): Unit = {
        val byte = base * p.vrf.rowsPerReg * p.vrf.rowBytes + index * (width / 8)
        for (j <- 0 until width / 8) {
          val at = byte + j
          rows(at / p.vrf.rowBytes)(at % p.vrf.rowBytes) = ((value >> (8 * j)) & 255).toInt
        }
      }
      var cases = 0
      for (op <- ops; vv <- Seq(true, false); width <- Seq(8, 16);
           lmul <- Seq(1, 2, 4); masked <- Seq(false, true);
           restarted <- Seq(false, true)) {
        for (row <- rows; j <- row.indices) row(j) = rng.nextInt(256)
        val vd = 8
        // Legal high-half overlap: source EMUL=lmul, destination EMUL=2*lmul.
        val vs2 = vd + lmul
        val vs1 = 0
        val maxVl = 512 / width * lmul
        val vl = if (cases % 7 == 0) maxVl else 1 + rng.nextInt(maxVl)
        val start = if (restarted) rng.nextInt(vl) else 0
        val scalar = BigInt(rng.nextInt(1 << width))
        val mask = (0 until maxVl).foldLeft(BigInt(0)) { (v, i) =>
          if (rng.nextBoolean()) v.setBit(i) else v
        }
        val expected = rows.map(_.clone())
        val accumulate = Set(ZveOp.vwmacc, ZveOp.vwmaccu, ZveOp.vwmaccsu, ZveOp.vwmaccus)(op)
        val aUnsigned = Set(ZveOp.vwmulu, ZveOp.vwmaccu, ZveOp.vwmaccsu)(op)
        val bUnsigned = Set(ZveOp.vwmulu, ZveOp.vwmulsu, ZveOp.vwmaccu, ZveOp.vwmaccus)(op)
        for (e <- start until vl if !masked || mask.testBit(e)) {
          val a = element(vs2, e, width)
          val b = if (vv) element(vs1, e, width) else scalar
          val av = if (aUnsigned) a else signed(a, width)
          val bv = if (bUnsigned) b else signed(b, width)
          val old = if (accumulate) element(vd, e, 2 * width) else BigInt(0)
          put(vd, e, 2 * width, (av * bv + old) & ((BigInt(1) << (2 * width)) - 1))
        }
        val result = rows.map(_.clone())
        for (i <- rows.indices) rows(i) = expected(i).clone()

        val instruction = (BigInt(vs2) << 20) | (BigInt(vs1) << 15) |
          (BigInt(vd) << 7) | (if (masked) BigInt(0) else BigInt(1) << 25)
        io.in.bits.issue.scalar.instruction.poke(instruction.U)
        io.in.bits.issue.scalar.rs1.poke((scalar & full64).U)
        io.in.bits.issue.scalar.vl.poke(vl.U)
        io.in.bits.issue.scalar.vstart.poke(start.U)
        io.in.bits.operation.poke(op.U)
        io.in.bits.sew.poke((if (width == 8) 0 else 1).U)
        io.in.bits.destinationWidth.poke((if (width == 8) 1 else 2).U)
        io.in.bits.vectorSource1.poke(vv.B)
        io.in.bits.slot.poke(1.U); io.in.bits.age.poke(3.U)
        io.mask.poke(mask.U)
        io.in.valid.poke(true.B); io.in.ready.expect(true.B)
        dut.clock.step(); io.in.valid.poke(false.B)

        val returns = mutable.Queue.empty[(Int, BigInt, BigInt, BigInt)]
        val acks = mutable.Queue.empty[(Int, Int)]
        var heldReturn: Option[(Int, BigInt, BigInt, BigInt)] = None
        var heldAck: Option[(Int, Int)] = None
        var done = false; var cycles = 0
        while (!done && cycles < 600) {
          io.vrf.read.ready.poke((rng.nextInt(4) != 0).B)
          io.vrf.write.ready.poke((rng.nextInt(3) != 0).B)
          if (heldReturn.isEmpty && returns.nonEmpty && rng.nextInt(3) != 0)
            heldReturn = Some(returns.dequeue())
          heldReturn.foreach { case (tag, a, b, old) =>
            io.vrf.returned.bits.tag.poke(tag.U)
            io.vrf.returned.bits.data(0).poke(a.U)
            io.vrf.returned.bits.data(1).poke(b.U)
            io.vrf.returned.bits.data(2).poke(old.U)
          }
          io.vrf.returned.valid.poke(heldReturn.nonEmpty.B)
          if (heldAck.isEmpty && acks.nonEmpty && rng.nextBoolean()) heldAck = Some(acks.dequeue())
          heldAck.foreach { case (tag, row) =>
            io.vrf.written.bits.tag.poke(tag.U); io.vrf.written.bits.row.poke(row.U)
          }
          io.vrf.written.valid.poke(heldAck.nonEmpty.B)
          def fire(v: Bool, r: Bool) = v.peek().litToBoolean && r.peek().litToBoolean
          if (fire(io.vrf.read.valid, io.vrf.read.ready)) {
            val req = io.vrf.read.bits
            val tag = req.tag.peek().litValue.toInt
            val data = (0 until 3).map { k =>
              val row = req.rows(k).peek().litValue.toInt
              if (req.enables.peek().litValue.testBit(k)) pack(rows(row)) else BigInt(0)
            }
            returns.enqueue((tag, data(0), data(1), data(2)))
          }
          if (fire(io.vrf.returned.valid, io.vrf.returned.ready)) heldReturn = None
          if (fire(io.vrf.write.valid, io.vrf.write.ready)) {
            val wr = io.vrf.write.bits
            val row = wr.row.peek().litValue.toInt
            val data = wr.data.peek().litValue
            val enables = wr.enables.peek().litValue
            for (j <- 0 until p.vrf.rowBytes if enables.testBit(j))
              rows(row)(j) = ((data >> (8 * j)) & 255).toInt
            acks.enqueue((wr.tag.peek().litValue.toInt, row))
          }
          if (fire(io.vrf.written.valid, io.vrf.written.ready)) heldAck = None
          done = fire(io.complete.valid, io.complete.ready)
          dut.clock.step(); cycles += 1
        }
        assert(done, s"MAC timeout op=$op vv=$vv sew=$width lmul=$lmul vl=$vl")
        assert(returns.isEmpty && heldReturn.isEmpty && acks.isEmpty && heldAck.isEmpty)
        for (i <- rows.indices) assert(rows(i).sameElements(result(i)),
          s"MAC mismatch op=$op vv=$vv sew=$width lmul=$lmul vl=$vl start=$start masked=$masked row=$i")
        io.drained.expect(true.B)
        cases += 1
      }
      assert(cases == 336)
    }
  }
}

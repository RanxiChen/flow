package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable
import scala.util.Random

class ZveR07AluSpec extends AnyFreeSpec with ChiselSim {
  private val p = ZveParams.soc(32, 32)
  "R07 ALU logic and shifts match an element model" in {
    simulate(new ZveAluVectorEngine(p)) { dut =>
      val io = dut.io; val rng = new Random(0x711)
      io.in.valid.poke(false.B); io.complete.ready.poke(true.B)
      io.vrf.read.ready.poke(false.B); io.vrf.returned.valid.poke(false.B)
      io.vrf.write.ready.poke(false.B); io.vrf.written.valid.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
      val rows = Array.fill(p.vrf.rows, p.vrf.rowBytes)(0)
      def pack(a: Array[Int]) = a.zipWithIndex.foldLeft(BigInt(0)) {
        case (v, (b, i)) => v | (BigInt(b) << (8 * i))
      }
      def elem(reg: Int, e: Int, width: Int): BigInt = {
        val at = reg * p.vrf.rowsPerReg * p.vrf.rowBytes + e * width / 8
        (0 until width / 8).foldLeft(BigInt(0)) { (v, j) =>
          val index = at + j
          v | (BigInt(rows(index / p.vrf.rowBytes)(index % p.vrf.rowBytes)) << (8 * j))
        }
      }
      def put(reg: Int, e: Int, width: Int, value: BigInt): Unit = {
        val at = reg * p.vrf.rowsPerReg * p.vrf.rowBytes + e * width / 8
        for (j <- 0 until width / 8) {
          val index = at + j
          rows(index / p.vrf.rowBytes)(index % p.vrf.rowBytes) = ((value >> (8 * j)) & 255).toInt
        }
      }
      val forms = Seq((ZveOp.vor, true, "vv"), (ZveOp.vor, false, "vx"),
        (ZveOp.vor, false, "vi"), (ZveOp.vxor, true, "vv"),
        (ZveOp.vxor, false, "vx"), (ZveOp.vxor, false, "vi"),
        (ZveOp.vsll, false, "vx"), (ZveOp.vsll, false, "vi"),
        (ZveOp.vsra, false, "vx"), (ZveOp.vsra, false, "vi"))
      var cases = 0
      for ((op, vv, form) <- forms; width <- Seq(8, 16, 32);
           masked <- Seq(false, true); restarted <- Seq(false, true)) {
        for (row <- rows; j <- row.indices) row(j) = rng.nextInt(256)
        val vl = if (cases % 5 == 0) 512 / width else 1 + rng.nextInt(512 / width)
        val start = if (restarted) rng.nextInt(vl) else 0
        val scalar = if (form == "vi") BigInt(0x1d) else BigInt(rng.nextLong())
        val mask = (0 until 512 / width).foldLeft(BigInt(0)) { (v, i) =>
          if (rng.nextBoolean()) v.setBit(i) else v
        }
        val before = rows.map(_.clone())
        val m = (BigInt(1) << width) - 1
        for (e <- start until vl if !masked || mask.testBit(e)) {
          val a = elem(4, e, width)
          val b = if (vv) elem(0, e, width) else scalar & m
          val amount = (scalar & (width - 1)).toInt
          val signedA = if (a.testBit(width - 1)) a - (BigInt(1) << width) else a
          val value = op match {
            case ZveOp.vor => a | b
            case ZveOp.vxor => a ^ b
            case ZveOp.vsll => a << amount
            case ZveOp.vsra => signedA >> amount
          }
          put(8, e, width, value & m)
        }
        val expected = rows.map(_.clone())
        for (i <- rows.indices) rows(i) = before(i).clone()

        val inst = (BigInt(4) << 20) | (BigInt(0) << 15) |
          (BigInt(8) << 7) | (if (masked) BigInt(0) else BigInt(1) << 25)
        io.in.bits.issue.scalar.instruction.poke(inst.U)
        io.in.bits.issue.scalar.rs1.poke((scalar & ((BigInt(1) << 64) - 1)).U)
        io.in.bits.issue.scalar.vl.poke(vl.U); io.in.bits.issue.scalar.vstart.poke(start.U)
        io.in.bits.operation.poke(op.U)
        io.in.bits.sew.poke((width match { case 8 => 0; case 16 => 1; case _ => 2 }).U)
        io.in.bits.vectorSource1.poke(vv.B); io.in.bits.vectorSource2.poke(true.B)
        io.in.bits.slot.poke(1.U); io.in.bits.age.poke(3.U)
        io.mask.poke(mask.U); io.in.valid.poke(true.B)
        io.in.ready.expect(true.B); dut.clock.step(); io.in.valid.poke(false.B)

        val returns = mutable.Queue.empty[(Int, BigInt, BigInt)]
        val acks = mutable.Queue.empty[(Int, Int)]
        var heldReturn: Option[(Int, BigInt, BigInt)] = None
        var heldAck: Option[(Int, Int)] = None
        var done = false; var cycle = 0
        while (!done && cycle < 300) {
          io.vrf.read.ready.poke((rng.nextInt(4) != 0).B)
          io.vrf.write.ready.poke((rng.nextInt(3) != 0).B)
          if (heldReturn.isEmpty && returns.nonEmpty && rng.nextBoolean()) heldReturn = Some(returns.dequeue())
          heldReturn.foreach { case (tag, a, b) =>
            io.vrf.returned.bits.tag.poke(tag.U)
            io.vrf.returned.bits.data(0).poke(a.U)
            io.vrf.returned.bits.data(1).poke(b.U)
            io.vrf.returned.bits.data(2).poke(0.U)
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
            returns.enqueue((req.tag.peek().litValue.toInt,
              pack(rows(req.rows(0).peek().litValue.toInt)),
              pack(rows(req.rows(1).peek().litValue.toInt))))
          }
          if (fire(io.vrf.returned.valid, io.vrf.returned.ready)) heldReturn = None
          if (fire(io.vrf.write.valid, io.vrf.write.ready)) {
            val wr = io.vrf.write.bits; val row = wr.row.peek().litValue.toInt
            val data = wr.data.peek().litValue; val enables = wr.enables.peek().litValue
            for (j <- 0 until p.vrf.rowBytes if enables.testBit(j))
              rows(row)(j) = ((data >> (8 * j)) & 255).toInt
            acks.enqueue((wr.tag.peek().litValue.toInt, row))
          }
          if (fire(io.vrf.written.valid, io.vrf.written.ready)) heldAck = None
          done = fire(io.complete.valid, io.complete.ready)
          dut.clock.step(); cycle += 1
        }
        assert(done, s"ALU timeout op=$op form=$form width=$width")
        for (i <- rows.indices) assert(rows(i).sameElements(expected(i)),
          s"ALU mismatch op=$op form=$form width=$width mask=$masked start=$start row=$i")
        io.drained.expect(true.B); cases += 1
      }
      assert(cases == 120)
    }
  }
}

package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable
import scala.util.Random

/** Coherent-line service model; the golden is a byte copy with architectural enables. */
class ZveR07StreamSpec extends AnyFreeSpec with ChiselSim {
  private def run(p: ZveParams, seed: Int): Unit = {
    simulate(new ZveStreamEngine(p)) { dut =>
      val io = dut.io; val rng = new Random(seed)
      val R = p.vrf.rowBytes; val L = p.lineBytes
      io.in.valid.poke(false.B); io.complete.ready.poke(true.B); io.mask.poke(0.U)
      io.vrf.read.ready.poke(false.B); io.vrf.returned.valid.poke(false.B)
      io.vrf.write.ready.poke(false.B); io.vrf.written.valid.poke(false.B)
      io.line.request.ready.poke(false.B); io.line.response.valid.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
      def packed(a: Array[Int]): BigInt = a.zipWithIndex.foldLeft(BigInt(0)) {
        case (v, (b, j)) => v | (BigInt(b) << (8 * j))
      }
      def exercise(offset: Int, width: Int, lmul: Int, vl: Int,
          store: Boolean, masked: Boolean, kind: String, crossPage: Boolean): Unit = {
        val va = (if (crossPage) 0x1fe0 else 0x1000) + offset
        val vd = 8; val eew = width match { case 8 => 0; case 16 => 1; case _ => 2 }
        val total = kind match {
          case "whole" => lmul * p.vrf.regBytes
          case "mask" => (vl + 7) / 8
          case _ => vl * width / 8
        }
        val vrf = Array.fill(p.vrf.rows, R)(rng.nextInt(256))
        val mem = mutable.Map.empty[BigInt, Array[Int]]
        def paLine(address: Int): BigInt = {
          val page = if (address < 0x2000) 0x100 else 0x200
          (BigInt(page) << (12 - 5)) | BigInt((address & 4095) / L)
        }
        def line(address: Int): Array[Int] = mem.getOrElseUpdate(paLine(address),
          Array.fill(L)(rng.nextInt(256)))
        for (i <- 0 until total) line(va + i)
        val beforeVrf = vrf.map(_.clone())
        val beforeMem = mem.map { case (k, v) => k -> v.clone() }.toMap
        val mask = (0 until 512).foldLeft(BigInt(0)) { (v, i) =>
          if (rng.nextBoolean()) v.setBit(i) else v
        }
        def enabled(i: Int) = kind != "normal" || !masked || mask.testBit(i / (width / 8))
        for (i <- 0 until total if enabled(i)) {
          val vrfByte = vd * p.vrf.rowsPerReg * R + i
          val row = vrfByte / R; val col = vrfByte % R
          val memory = line(va + i); val mcol = (va + i) % L
          if (store) memory(mcol) = vrf(row)(col)
          else vrf(row)(col) = memory(mcol)
        }
        val expectedVrf = vrf.map(_.clone())
        val expectedMem = mem.map { case (k, v) => k -> v.clone() }.toMap
        for (i <- vrf.indices) vrf(i) = beforeVrf(i).clone()
        for ((k, v) <- beforeMem) mem(k) = v.clone()

        val opcode = if (store) 0x27 else 0x07
        val vs2 = if (kind == "whole") 8 else if (kind == "mask") 11 else 0
        val nf = if (kind == "whole") lmul - 1 else 0
        val inst = (BigInt(nf) << 29) | (BigInt(vs2) << 20) |
          (BigInt(if (masked) 0 else 1) << 25) | (BigInt(vd) << 7) | opcode
        io.in.bits.issue.scalar.instruction.poke(inst.U)
        io.in.bits.issue.scalar.rs1.poke(va.U)
        io.in.bits.issue.scalar.vl.poke(vl.U)
        io.in.bits.issue.scalar.vstart.poke(0.U)
        io.in.bits.issue.pre.valid.poke(true.B)
        io.in.bits.issue.pre.page0.poke(0x100.U)
        io.in.bits.issue.pre.page1.poke(0x200.U)
        io.in.bits.destinationWidth.poke(eew.U)
        io.in.bits.whole.poke((kind == "whole").B)
        io.in.bits.slot.poke(1.U); io.in.bits.age.poke(3.U)
        io.mask.poke(mask.U); io.in.valid.poke(true.B)
        io.in.ready.expect(true.B); dut.clock.step(); io.in.valid.poke(false.B)

        val vrfReturns = mutable.Queue.empty[(Int, BigInt, BigInt)]
        val writeAcks = mutable.Queue.empty[(Int, Int)]
        val lineResponses = mutable.ArrayBuffer.empty[(Int, BigInt, Int)]
        var heldVrf: Option[(Int, BigInt, BigInt)] = None
        var heldWrite: Option[(Int, Int)] = None
        var heldLine: Option[(Int, BigInt, Int)] = None
        var cycle = 0; var done = false; var maxBusy = 0
        while (!done && cycle < 4000) {
          io.vrf.read.ready.poke((rng.nextInt(4) != 0).B)
          io.vrf.write.ready.poke((rng.nextInt(3) != 0).B)
          io.line.request.ready.poke((rng.nextInt(3) != 0).B)
          if (heldVrf.isEmpty && vrfReturns.nonEmpty && rng.nextBoolean()) heldVrf = Some(vrfReturns.dequeue())
          heldVrf.foreach { case (tag, a, b) =>
            io.vrf.returned.bits.tag.poke(tag.U)
            io.vrf.returned.bits.data(0).poke(a.U)
            io.vrf.returned.bits.data(1).poke(b.U)
            io.vrf.returned.bits.data(2).poke(0.U)
          }
          io.vrf.returned.valid.poke(heldVrf.nonEmpty.B)
          if (heldWrite.isEmpty && writeAcks.nonEmpty && rng.nextBoolean()) heldWrite = Some(writeAcks.dequeue())
          heldWrite.foreach { case (tag, row) =>
            io.vrf.written.bits.tag.poke(tag.U); io.vrf.written.bits.row.poke(row.U)
          }
          io.vrf.written.valid.poke(heldWrite.nonEmpty.B)
          if (heldLine.isEmpty) {
            val ready = lineResponses.indices.filter(i => lineResponses(i)._3 <= cycle)
            if (ready.nonEmpty && rng.nextInt(3) != 0) heldLine = Some(lineResponses.remove(ready(rng.nextInt(ready.size))))
          }
          heldLine.foreach { case (id, data, _) =>
            io.line.response.bits.id.poke(id.U)
            io.line.response.bits.data.poke(data.U)
            io.line.response.bits.error.poke(false.B)
          }
          io.line.response.valid.poke(heldLine.nonEmpty.B)
          def fire(v: Bool, r: Bool) = v.peek().litToBoolean && r.peek().litToBoolean
          if (fire(io.vrf.read.valid, io.vrf.read.ready)) {
            val req = io.vrf.read.bits
            val a = packed(vrf(req.rows(0).peek().litValue.toInt))
            val b = packed(vrf(req.rows(1).peek().litValue.toInt))
            vrfReturns.enqueue((req.tag.peek().litValue.toInt, a, b))
          }
          if (fire(io.vrf.returned.valid, io.vrf.returned.ready)) heldVrf = None
          if (fire(io.line.request.valid, io.line.request.ready)) {
            val req = io.line.request.bits
            val id = req.id.peek().litValue.toInt
            val lineAddress = req.line.peek().litValue
            val data = mem.getOrElseUpdate(lineAddress, Array.fill(L)(rng.nextInt(256)))
            if (store) {
              req.write.expect(true.B)
              val bits = req.data.peek().litValue; val maskBits = req.mask.peek().litValue
              for (j <- 0 until L if maskBits.testBit(j)) data(j) = ((bits >> (8 * j)) & 255).toInt
              lineResponses += ((id, BigInt(0), cycle + 1 + rng.nextInt(8)))
            } else {
              req.write.expect(false.B)
              lineResponses += ((id, packed(data), cycle + 1 + rng.nextInt(8)))
            }
          }
          if (fire(io.line.response.valid, io.line.response.ready)) heldLine = None
          if (fire(io.vrf.write.valid, io.vrf.write.ready)) {
            val req = io.vrf.write.bits
            val row = req.row.peek().litValue.toInt
            val bits = req.data.peek().litValue; val maskBits = req.enables.peek().litValue
            for (j <- 0 until R if maskBits.testBit(j)) vrf(row)(j) = ((bits >> (8 * j)) & 255).toInt
            writeAcks.enqueue((req.tag.peek().litValue.toInt, row))
          }
          if (fire(io.vrf.written.valid, io.vrf.written.ready)) heldWrite = None
          maxBusy = math.max(maxBusy, lineResponses.size + heldLine.size)
          done = fire(io.complete.valid, io.complete.ready)
          dut.clock.step(); cycle += 1
        }
        assert(done, s"stream timeout R=$R offset=$offset width=$width lmul=$lmul vl=$vl store=$store kind=$kind")
        io.fatal.expect(false.B); io.drained.expect(true.B)
        for (i <- vrf.indices) assert(vrf(i).sameElements(expectedVrf(i)),
          s"VRF mismatch R=$R offset=$offset width=$width lmul=$lmul vl=$vl store=$store masked=$masked kind=$kind row=$i")
        for ((k, v) <- expectedMem) assert(mem(k).sameElements(v),
          s"memory mismatch R=$R offset=$offset width=$width lmul=$lmul vl=$vl store=$store masked=$masked kind=$kind line=$k")
        assert(maxBusy <= p.streamCredits)
      }
      for (offset <- 0 until 32; width <- Seq(8, 16, 32); lmul <- Seq(1, 2, 4, 8);
           store <- Seq(false, true)) {
        val maxVl = 512 / width * lmul
        val vl = if ((offset + lmul) % 7 == 0) maxVl else if (offset % 7 == 0) 1 else 1 + rng.nextInt(maxVl)
        exercise(offset, width, lmul, vl, store, (offset + width + lmul) % 2 == 0,
          "normal", offset % 5 == 0)
      }
      for (offset <- Seq(0, 1, 15, 31); n <- Seq(1, 2, 4, 8); store <- Seq(false, true))
        exercise(offset, 8, n, 1, store, masked = false, "whole", crossPage = true)
      for (offset <- Seq(0, 1, 15, 31); vl <- Seq(1, 8, 63, 512); store <- Seq(false, true))
        exercise(offset, 8, 1, vl, store, masked = false, "mask", crossPage = true)
      // LMUL=8 spans the largest legal vl for each SEW. Sweep every value in
      // that range for both load and store while rotating offset and mask.
      for (width <- Seq(8, 16, 32); vl <- 1 to (512 / width * 8);
           store <- Seq(false, true)) {
        val offset = (vl - 1) % 32
        exercise(offset, width, 8, vl, store, masked = vl % 2 == 0,
          "normal", crossPage = vl % 7 == 0)
      }
    }
  }
  "R07 32-byte rows use the byte reference model" in run(ZveParams.soc(32, 32), 0x3207)
  "default 64-byte rows retain the byte reference behavior" in run(ZveParams(), 0x6407)
}

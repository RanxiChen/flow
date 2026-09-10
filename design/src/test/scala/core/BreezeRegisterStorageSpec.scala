package flow.core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.fpu.BreezeFpRegFile
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeRegisterStorageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "preserve integer reset zeros, x0, asynchronous reads and both bypass ports" in {
    simulate(new RegFile(64)) { dut =>
      val reference = Array.fill[BigInt](32)(0)
      val rng = new scala.util.Random(0x52464750L)
      dut.io.rd_en.poke(false.B)
      dut.io.rd_addr.poke(0.U); dut.io.rd_data.poke(0.U)
      dut.io.rs1_addr.poke(0.U); dut.io.rs2_addr.poke(0.U)
      def scan(): Unit = {
        dut.io.rd_en.poke(false.B)
        for (i <- 0 until 32) {
          dut.io.rs1_addr.poke(i.U); dut.io.rs2_addr.poke((31-i).U)
          dut.io.rs1_data.expect(reference(i).U)
          dut.io.rs2_data.expect(reference(31-i).U)
        }
      }
      for (epoch <- 0 until 2) {
        // Also reset with a write pending, so stale RAM data cannot become valid.
        dut.io.rd_en.poke(true.B); dut.io.rd_addr.poke(7.U)
        dut.io.rd_data.poke("hffffffffffffffff".U)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        for (i <- reference.indices) reference(i) = 0
        scan()
        for (cycle <- 0 until 160) {
          val rd = if (cycle < 32) cycle else rng.nextInt(32)
          val a = if (cycle % 3 == 0) rd else rng.nextInt(32)
          val b = if (cycle % 3 != 0) rd else rng.nextInt(32)
          val data = BigInt(64, rng)
          val enable = cycle < 32 || cycle % 5 != 0
          dut.io.rd_addr.poke(rd.U); dut.io.rd_data.poke(data.U)
          dut.io.rd_en.poke(enable.B)
          dut.io.rs1_addr.poke(a.U); dut.io.rs2_addr.poke(b.U)
          def expected(r: Int): BigInt =
            if (r == 0) BigInt(0) else if (enable && r == rd) data else reference(r)
          dut.io.rs1_data.expect(expected(a).U); dut.io.rs2_data.expect(expected(b).U)
          dut.clock.step()
          if (enable && rd != 0) reference(rd) = data
          if (cycle % 16 == 15) scan()
        }
        scan()
      }
    }
  }

  "preserve all FP bits, writable f0, three bypass ports and reset after use" in {
    simulate(new BreezeFpRegFile) { dut =>
      val reference = Array.fill[BigInt](32)(0)
      val rng = new scala.util.Random(0x52464650L)
      dut.io.rdEn.poke(false.B); dut.io.rdAddr.poke(0.U); dut.io.rdData.poke(0.U)
      dut.io.rs1Addr.poke(0.U); dut.io.rs2Addr.poke(0.U); dut.io.rs3Addr.poke(0.U)
      val addresses = Seq(dut.io.rs1Addr, dut.io.rs2Addr, dut.io.rs3Addr)
      val outputs = Seq(dut.io.rs1Data, dut.io.rs2Data, dut.io.rs3Data)
      def scan(): Unit = {
        dut.io.rdEn.poke(false.B)
        for (i <- 0 until 32; port <- 0 until 3) {
          addresses(port).poke(i.U); outputs(port).expect(reference(i).U)
        }
      }
      for (epoch <- 0 until 2) {
        dut.io.rdEn.poke(true.B); dut.io.rdAddr.poke(0.U)
        dut.io.rdData.poke("hffffffffffffffff".U)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        for (i <- reference.indices) reference(i) = 0
        scan()
        for (cycle <- 0 until 160) {
          val rd = if (cycle < 32) cycle else rng.nextInt(32)
          val data = if (cycle == 0) BigInt("ffffffff7fc00001", 16) else BigInt(64, rng)
          val enable = cycle < 32 || cycle % 5 != 0
          dut.io.rdAddr.poke(rd.U); dut.io.rdData.poke(data.U); dut.io.rdEn.poke(enable.B)
          for (port <- 0 until 3) {
            val a = if (cycle % 4 == port || cycle % 4 == 3) rd else rng.nextInt(32)
            addresses(port).poke(a.U)
            outputs(port).expect((if (enable && a == rd) data else reference(a)).U)
          }
          dut.clock.step()
          if (enable) reference(rd) = data
          if (cycle % 16 == 15) scan()
        }
        scan()
      }
    }
  }
}

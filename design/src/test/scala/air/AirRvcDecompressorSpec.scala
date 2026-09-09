package flow.air

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class AirRvcDecompressorSpec extends AnyFreeSpec with ChiselSim {
  "expand representative RV64C encodings and reject reserved instructions" in {
    simulate(new AirRvcDecompressor) { dut =>
      val cNop = 0x0001
      dut.io.in.poke(cNop.U); dut.clock.step()
      assert(dut.io.legal.peek().litToBoolean)
      assert(dut.io.out.peekValue().asBigInt == BigInt("00000013", 16))

      val cEbreak = 0x9002
      dut.io.in.poke(cEbreak.U); dut.clock.step()
      assert(dut.io.legal.peek().litToBoolean)
      assert(dut.io.out.peekValue().asBigInt == BigInt("00100073", 16))

      dut.io.in.poke(0x918a.U); dut.clock.step() // C.ADD x3,x2
      assert(dut.io.legal.peek().litToBoolean)
      assert(dut.io.out.peekValue().asBigInt == BigInt("002181b3", 16),
        s"C.ADD expanded to 0x${dut.io.out.peekValue().asBigInt.toString(16)}")

      dut.io.in.poke(0x4095.U); dut.clock.step() // C.LI x1,5
      assert(dut.io.legal.peek().litToBoolean)
      assert(dut.io.out.peekValue().asBigInt == BigInt("00500093", 16),
        s"C.LI expanded to 0x${dut.io.out.peekValue().asBigInt.toString(16)}")

      dut.io.in.poke(0.U); dut.clock.step() // reserved C.ADDI4SPN nzuimm=0
      assert(!dut.io.legal.peek().litToBoolean)
    }
  }
}

package flow.air

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class AirRegisterFileSpec extends AnyFreeSpec with ChiselSim {
  "store and synchronously return one byte from the single 1R1W RAM" in {
    simulate(new AirRegisterFile) { dut =>
      dut.io.readEnable.poke(false.B)
      dut.io.readReg.poke(0.U); dut.io.readByte.poke(0.U)
      dut.io.writeEnable.poke(true.B)
      dut.io.writeReg.poke(1.U); dut.io.writeByte.poke(0.U); dut.io.writeData.poke(5.U)
      dut.clock.step()
      dut.io.writeEnable.poke(false.B)
      dut.io.readEnable.poke(true.B); dut.io.readReg.poke(1.U); dut.io.readByte.poke(0.U)
      dut.clock.step()
      assert(dut.io.readData.peekValue().asBigInt == 5)
      dut.io.readEnable.poke(false.B)
      dut.clock.step()
      assert(dut.io.readData.peekValue().asBigInt == 5)
    }
  }
}

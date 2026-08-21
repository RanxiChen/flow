package flow.platform

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeLinuxPmaSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def query(dut: PMAChecker, address: BigInt, sizeLog2: Int,
                    access: PMAAccessType.Type): Unit = {
    dut.io.query.addr.poke(address.U)
    dut.io.query.sizeLog2.poke(sizeLog2.U)
    dut.io.query.accessType.poke(access)
  }

  "Linux 16550 region should be readable and writable device memory" in {
    simulate(new PMAChecker) { dut =>
      query(dut, BigInt("13000000", 16), 0, PMAAccessType.Load)
      dut.io.result.regionHit.expect(true.B)
      dut.io.result.allowed.expect(true.B)
      dut.io.result.cacheable.expect(false.B)
      dut.io.result.device.expect(true.B)

      query(dut, BigInt("13000007", 16), 0, PMAAccessType.Store)
      dut.io.result.allowed.expect(true.B)

      query(dut, BigInt("13000000", 16), 2, PMAAccessType.Fetch)
      dut.io.result.allowed.expect(false.B)
    }
  }

  "Linux DDR PMA should cover exactly 256 MiB" in {
    simulate(new PMAChecker) { dut =>
      query(dut, BigInt("8fffffff", 16), 0, PMAAccessType.Load)
      dut.io.result.allowed.expect(true.B)
      dut.io.result.cacheable.expect(true.B)
      dut.io.result.device.expect(false.B)

      query(dut, BigInt("90000000", 16), 0, PMAAccessType.Load)
      dut.io.result.regionHit.expect(false.B)
      dut.io.result.allowed.expect(false.B)
    }
  }

  "MCU boot ROM should remain non-writable" in {
    simulate(new PMAChecker) { dut =>
      query(dut, BigInt("10000000", 16), 0, PMAAccessType.Store)
      dut.io.result.regionHit.expect(true.B)
      dut.io.result.allowed.expect(false.B)
    }
  }
}

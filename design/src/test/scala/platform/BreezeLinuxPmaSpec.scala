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

  "native LiteUART CSR page should be readable and writable device memory" in {
    simulate(new PMAChecker) { dut =>
      query(dut, BigInt("12001000", 16), 0, PMAAccessType.Load)
      dut.io.result.regionHit.expect(true.B)
      dut.io.result.allowed.expect(true.B)
      dut.io.result.cacheable.expect(false.B)
      dut.io.result.device.expect(true.B)

      query(dut, BigInt("12001014", 16), 0, PMAAccessType.Store)
      dut.io.result.allowed.expect(true.B)

      query(dut, BigInt("12001000", 16), 2, PMAAccessType.Fetch)
      dut.io.result.allowed.expect(false.B)
    }
  }

  "DDR PMA should cover 2 GiB including the former 256 MiB and 1 GiB boundaries" in {
    simulate(new PMAChecker) { dut =>
      for (address <- Seq("80000000", "90000000", "bffff000", "c0000000", "fffff000")) {
        for (access <- Seq(PMAAccessType.Fetch, PMAAccessType.Load, PMAAccessType.Store)) {
          query(dut, BigInt(address, 16), 3, access)
          dut.io.result.regionHit.expect(true.B)
          dut.io.result.allowed.expect(true.B)
          dut.io.result.cacheable.expect(true.B)
          dut.io.result.device.expect(false.B)
        }
      }
      // Legal accesses ending at the final byte of the 32-bit physical space.
      for ((address, size) <- Seq(("ffffffff", 0), ("fffffff8", 3), ("ffffffe0", 5))) {
        query(dut, BigInt(address, 16), size, PMAAccessType.Store)
        dut.io.result.allowed.expect(true.B)
      }
      // Crossing 4 GiB must not wrap around and alias a low physical address.
      for ((address, size) <- Seq(("7fffffff", 0), ("100000000", 0),
                                 ("fffffffc", 3), ("fffffff0", 5))) {
        query(dut, BigInt(address, 16), size, PMAAccessType.Store)
        dut.io.result.regionHit.expect(false.B)
        dut.io.result.allowed.expect(false.B)
      }
    }
  }

  "MCU boot ROM should remain non-writable" in {
    simulate(new PMAChecker) { dut =>
      query(dut, BigInt("10000000", 16), 0, PMAAccessType.Store)
      dut.io.result.regionHit.expect(true.B)
      dut.io.result.allowed.expect(false.B)
    }
  }

  "SRAM PMA should end at the actual KCU105 64 KiB boundary" in {
    simulate(new PMAChecker) { dut =>
      query(dut, BigInt("1100fff8", 16), 3, PMAAccessType.Store)
      dut.io.result.allowed.expect(true.B)
      query(dut, BigInt("11010000", 16), 0, PMAAccessType.Load)
      dut.io.result.allowed.expect(false.B)
    }
  }
}

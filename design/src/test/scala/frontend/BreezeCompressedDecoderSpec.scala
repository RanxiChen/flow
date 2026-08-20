package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class BreezeCompressedDecoderSpec extends AnyFreeSpec with ChiselSim {
  private def check(dut: BreezeCompressedDecoder, in: Int, out: BigInt,
                    illegal: Boolean = false): Unit = {
    dut.io.in.poke(in.U)
    dut.clock.step(1)
    dut.io.out.expect(out.U)
    dut.io.illegal.expect(illegal.B)
  }

  "expand representative RV64C integer instructions and reject reserved encodings" in {
    simulate(new BreezeCompressedDecoder(enableDouble = true)) { dut =>
      check(dut, 0x0001, 0x00000013L) // c.nop
      check(dut, 0x0085, 0x00108093L) // c.addi ra, 1
      check(dut, 0x557d, 0xfff00513L) // c.li a0, -1
      check(dut, 0x8082, 0x00008067L) // c.jr ra
      check(dut, 0x9002, 0x00100073L) // c.ebreak
      check(dut, 0x0000, 0x00000013L, illegal = true) // reserved c.addi4spn 0
    }
  }
}

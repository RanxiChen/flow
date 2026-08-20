package flow.backend

import chisel3._
import flow.config.BackendConfig
import flow.fpu.BreezeFpChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeBackendFpSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import BreezeBackendFpTestUtils._

  "trap FP instructions while mstatus.FS is Off" in {
    simulate(new BreezeBackend(BackendConfig(), enabledebug = true)) { dut =>
      driveIdle(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      val fadd = encodeOpFp(0x00, 1, rs2 = 2, rm = 0, rd = 3, rs1 = 1)
      issue(dut, 0x200, fadd)
      var trapped = false
      for (_ <- 0 until 40) {
        if (dut.io.debug.get.memWbValid.peek().litToBoolean &&
            dut.io.debug.get.memWbInst.peekValue().asBigInt == fadd) {
          dut.io.debug.get.memWbException.expect(true.B)
          trapped = true
        }
        dut.clock.step(1)
      }
      trapped mustBe true
      dut.io.debug.get.csrMcause.expect(2.U) // illegal instruction
    }
  }

  "execute dependent FP64 arithmetic/FMA and commit sticky fflags" in {
    simulate(new BreezeBackend(BackendConfig(), enabledebug = true)) { dut =>
      driveIdle(dut)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pc = BigInt(0x400)
      def allocPc(): BigInt = { val result = pc; pc += 4; result }
      def run(inst: BigInt): BigInt = {
        issue(dut, allocPc(), inst)
        waitForWb(dut, inst)
      }
      enableFp(dut, () => allocPc())

      // x1=double(1.0), x2=double(2.0)
      run(encodeLui(1, 0x3ff00))
      run(encodeSlli(1, 1, 32))
      run(encodeLui(2, 0x40000))
      run(encodeSlli(2, 2, 32))
      run(encodeOpFp(0x1e, 1, 0, 0, rd = 1, rs1 = 1)) // FMV.D.X f1,x1
      run(encodeOpFp(0x1e, 1, 0, 0, rd = 2, rs1 = 2)) // FMV.D.X f2,x2

      val fadd = encodeOpFp(0x00, 1, rs2 = 2, rm = 0, rd = 3, rs1 = 1)
      run(fadd)
      val moveAdd = encodeOpFp(0x1c, 1, 0, 0, rd = 3, rs1 = 3)
      run(moveAdd) mustBe BigInt("4008000000000000", 16) // 3.0

      val fmadd = encodeFma(0x43, 1, rs3 = 1, rs2 = 2, rs1 = 1, rm = 0, rd = 4)
      run(fmadd)
      val moveFma = encodeOpFp(0x1c, 1, 0, 0, rd = 4, rs1 = 4)
      run(moveFma) mustBe BigInt("4008000000000000", 16) // 1*2+1

      // f0 is +0.0 after reset.  Division by zero must set DZ and a later
      // exact operation must not clear the sticky flag.
      run(encodeOpFp(0x03, 1, rs2 = 0, rm = 0, rd = 5, rs1 = 1))
      run(fadd)
      val readFlags = encodeCsr(2, rd = 5, csr = 0x001, rs1 = 0)
      (run(readFlags) & 0x1f) mustBe 8
    }
  }
}

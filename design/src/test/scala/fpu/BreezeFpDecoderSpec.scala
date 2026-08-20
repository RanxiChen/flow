package flow.fpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class BreezeFpDecoderSpec extends AnyFreeSpec with ChiselSim {
  private def opFp(funct5: Int, fmt: Int, rs2: Int, funct3: Int,
      rd: Int = 1, rs1: Int = 2): BigInt =
    (BigInt(funct5) << 27) | (BigInt(fmt) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | BigInt(0x53)

  private def fma(opcode: Int, fmt: Int, rm: Int): BigInt =
    (BigInt(4) << 27) | (BigInt(fmt) << 25) | (BigInt(3) << 20) |
      (BigInt(2) << 15) | (BigInt(rm) << 12) | (BigInt(1) << 7) | opcode

  private def fpMem(opcode: Int, funct3: Int): BigInt =
    (BigInt(8) << 20) | (BigInt(2) << 15) | (BigInt(funct3) << 12) |
      (BigInt(1) << 7) | opcode

  "decode every scalar RV64F/RV64D operation family" in {
    simulate(new BreezeFpDecoder) { dut =>
      case class Vector(inst: BigInt, operation: Int, fprWrite: Boolean,
          gprWrite: Boolean, usesThird: Boolean = false)
      val vectors = Seq(
        Vector(opFp(0x00, 0, 3, 0), BreezeFpOp.ADD, true, false),       // FADD.S
        Vector(opFp(0x01, 1, 3, 7), BreezeFpOp.ADD, true, false),       // FSUB.D dyn rm
        Vector(opFp(0x02, 1, 3, 0), BreezeFpOp.MUL, true, false),       // FMUL.D
        Vector(opFp(0x03, 0, 3, 0), BreezeFpOp.DIV, true, false),       // FDIV.S
        Vector(opFp(0x0b, 1, 0, 0), BreezeFpOp.SQRT, true, false),      // FSQRT.D
        Vector(opFp(0x04, 0, 3, 2), BreezeFpOp.SGNJ, true, false),      // FSGNJX.S
        Vector(opFp(0x05, 1, 3, 1), BreezeFpOp.MINMAX, true, false),    // FMAX.D
        Vector(opFp(0x08, 1, 0, 0), BreezeFpOp.F2F, true, false),       // FCVT.D.S
        Vector(opFp(0x14, 0, 3, 2), BreezeFpOp.CMP, false, true),       // FEQ.S
        Vector(opFp(0x18, 1, 3, 0), BreezeFpOp.F2I, false, true),       // FCVT.LU.D
        Vector(opFp(0x1a, 0, 2, 0), BreezeFpOp.I2F, true, false),       // FCVT.S.L
        Vector(opFp(0x1c, 1, 0, 1), BreezeFpOp.CLASSIFY, false, true),  // FCLASS.D
        Vector(fma(0x43, 0, 0), BreezeFpOp.FMADD, true, false, true),
        Vector(fma(0x4b, 1, 0), BreezeFpOp.FNMSUB, true, false, true)
      )

      vectors.foreach { vector =>
        dut.io.inst.poke(vector.inst.U)
        dut.clock.step(1)
        dut.io.ctrl.valid.expect(true.B)
        dut.io.illegal.expect(false.B)
        dut.io.ctrl.operation.expect(vector.operation.U)
        dut.io.ctrl.writesFpr.expect(vector.fprWrite.B)
        dut.io.ctrl.writesGpr.expect(vector.gprWrite.B)
        dut.io.ctrl.usesFpr3.expect(vector.usesThird.B)
      }

      dut.io.inst.poke(fpMem(0x07, 2).U) // FLW
      dut.clock.step(1)
      dut.io.ctrl.valid.expect(true.B)
      dut.io.ctrl.isLoad.expect(true.B)
      dut.io.ctrl.isDouble.expect(false.B)

      dut.io.inst.poke(fpMem(0x27, 3).U) // FSD
      dut.clock.step(1)
      dut.io.ctrl.valid.expect(true.B)
      dut.io.ctrl.isStore.expect(true.B)
      dut.io.ctrl.isDouble.expect(true.B)

      dut.io.inst.poke(opFp(0x1c, 1, 0, 0).U) // FMV.X.D
      dut.clock.step(1)
      dut.io.ctrl.localOp.expect(BreezeFpLocalOp.FMV_X.U)
      dut.io.ctrl.writesGpr.expect(true.B)

      dut.io.inst.poke(opFp(0x1e, 0, 0, 0).U) // FMV.W.X
      dut.clock.step(1)
      dut.io.ctrl.localOp.expect(BreezeFpLocalOp.FMV_F.U)
      dut.io.ctrl.writesFpr.expect(true.B)
    }
  }

  "reject reserved formats, rounding modes and malformed square roots" in {
    simulate(new BreezeFpDecoder) { dut =>
      val illegal = Seq(
        opFp(0x00, 2, 3, 0), // reserved fmt
        opFp(0x00, 0, 3, 5), // reserved rm
        opFp(0x0b, 1, 1, 0), // FSQRT requires rs2=0
        opFp(0x08, 1, 1, 0), // source and destination formats equal
        fpMem(0x07, 1)        // unsupported FP load width
      )
      illegal.foreach { inst =>
        dut.io.inst.poke(inst.U)
        dut.clock.step(1)
        dut.io.ctrl.valid.expect(false.B)
        dut.io.illegal.expect(true.B)
      }
    }
  }
}

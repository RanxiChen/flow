package flow.sim

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCoreSim extends AnyFreeSpec with Matchers {
    "BreezeCore Run simulate" in {
        val bootAddr = BigInt("10000000", 16)
        val program = Seq(
            BreezeCoreSimSupport.encodeAddi(rd = 1, rs1 = 0, imm = 42),
            BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 0, imm = 256),
            BreezeCoreSimSupport.encodeStore(rs1 = 3, rs2 = 1, imm = 0, funct3 = 3),
            BreezeCoreSimSupport.encodeLoad(rd = 2, rs1 = 3, imm = 0, funct3 = 3),
            BreezeCoreSimSupport.EstopInst
        )
        val memory = BreezeCoreSimSupport.buildInstructionMemory(program, bootAddr)
        val result = BreezeCoreSimRunner.run(memory, bootAddr = bootAddr)

        result.timedOut mustBe false
    }

    "BreezeCore should stop on an architectural-test exit write" in {
        val bootAddr = BigInt("10000000", 16)
        val exitAddress = BigInt(0x100)
        val program = Seq(
            BreezeCoreSimSupport.encodeAddi(rd = 1, rs1 = 0, imm = 1),
            BreezeCoreSimSupport.encodeAddi(rd = 2, rs1 = 0, imm = exitAddress.toInt),
            BreezeCoreSimSupport.encodeStore(rs1 = 2, rs2 = 1, imm = 0, funct3 = 2),
            BreezeCoreSimSupport.EstopInst
        )
        val memory = BreezeCoreSimSupport.buildInstructionMemory(program, bootAddr)
        val result = BreezeCoreSimRunner.runWithTandemTrace(
          memory,
          coreCfg = flow.config.BreezeCoreConfig(
            useFASE = false,
            enableTandem = true,
            useGShare = false
          ),
          bootAddr = bootAddr,
          maxCycles = 1000,
          exitAddress = Some(exitAddress)
        ).result

        result.timedOut mustBe false
        result.exitCode mustBe Some(BigInt(1))
    }
}

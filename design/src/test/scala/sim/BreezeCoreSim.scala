package flow.sim

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCoreSim extends AnyFreeSpec with Matchers {
    "BreezeCore Run simulate" in {
        val program = Seq(
            BreezeCoreSimSupport.encodeAddi(rd = 1, rs1 = 0, imm = 42),
            BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 0, imm = 256),
            BreezeCoreSimSupport.encodeStore(rs1 = 3, rs2 = 1, imm = 0, funct3 = 3),
            BreezeCoreSimSupport.encodeLoad(rd = 2, rs1 = 3, imm = 0, funct3 = 3),
            BreezeCoreSimSupport.EstopInst
        )
        val memory = BreezeCoreSimSupport.buildInstructionMemory(program)
        val result = BreezeCoreSimRunner.run(memory)

        result.timedOut mustBe false
    }
}

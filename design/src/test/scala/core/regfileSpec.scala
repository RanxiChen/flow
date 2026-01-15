package core

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class regfileSpec extends AnyFreeSpec with Matchers with ChiselSim {
    "regfile will async read" in {
        simulate(new RocketRegFile){dut =>
            dut.reset.poke(true.B)
            dut.clock.step(5)
            dut.reset.poke(false.B)
            dut.io.rs1_addr.poke(0.U)
            dut.io.rs2_addr.poke(0.U)
            //give initial values
            for(i <- 0 until 32){
                dut.io.rd_en.poke(true.B)
                dut.io.rd_addr.poke(i.U)
                dut.io.rd_data.poke((i+6).U)
                dut.clock.step(1)
            }
            dut.io.rd_en.poke(false.B)
            //async read check
            for(i <- 1 until 32){
                dut.io.rs1_addr.poke(i.U)
                dut.io.rs2_addr.poke(i.U)
                //println(s"rs1_addr: ${i}, rs1_data: ${dut.io.rs1_data.peek().litValue}, rs2_data: ${dut.io.rs2_data.peek().litValue}")
                //dut.clock.step(1)
                dut.io.rs1_data.expect(i+6)
                dut.io.rs2_data.expect(i+6)
            }
        }
    }
}
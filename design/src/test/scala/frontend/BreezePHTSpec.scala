package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezePHTSpec extends AnyFreeSpec with Matchers with ChiselSim {
    private val GhrLength = 4
    private val IndexMask = (1 << GhrLength) - 1

    "BreezePHT should xor the aligned PC with GHR and start weakly not-taken" in {
        simulate(new BreezePHT(vlen = 64, ghrLength = GhrLength)) { dut =>
            dut.io.predict.valid.poke(false.B)
            dut.io.predict.pc.poke(0.U)
            dut.io.predict.ghr.poke(0.U)
            dut.io.update.valid.poke(false.B)
            dut.io.update.idx.poke(0.U)
            dut.io.update.taken.poke(false.B)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            val pc = 0x134
            val ghr = 0xb
            val expectedIdx = ((pc >> 2) & IndexMask) ^ ghr
            dut.io.predict.valid.poke(true.B)
            dut.io.predict.pc.poke(pc.U)
            dut.io.predict.ghr.poke(ghr.U)
            dut.io.resp.valid.expect(true.B)
            dut.io.resp.idx.expect(expectedIdx.U)
            dut.io.resp.taken.expect(false.B)

            dut.io.predict.valid.poke(false.B)
            dut.io.resp.valid.expect(false.B)
            dut.io.resp.taken.expect(false.B)
        }
    }

    "BreezePHT should train and saturate its two-bit counters" in {
        simulate(new BreezePHT(vlen = 64, ghrLength = GhrLength)) { dut =>
            val pc = 0x100
            val ghr = 0x3
            val idx = ((pc >> 2) & IndexMask) ^ ghr

            def predictTaken(expected: Boolean): Unit = {
                dut.io.predict.valid.poke(true.B)
                dut.io.predict.pc.poke(pc.U)
                dut.io.predict.ghr.poke(ghr.U)
                dut.io.resp.idx.expect(idx.U)
                dut.io.resp.taken.expect(expected.B)
            }
            def train(taken: Boolean, count: Int = 1): Unit = {
                for (_ <- 0 until count) {
                    dut.io.update.valid.poke(true.B)
                    dut.io.update.idx.poke(idx.U)
                    dut.io.update.taken.poke(taken.B)
                    dut.clock.step(1)
                }
                dut.io.update.valid.poke(false.B)
            }

            dut.io.predict.valid.poke(false.B)
            dut.io.predict.pc.poke(0.U)
            dut.io.predict.ghr.poke(0.U)
            dut.io.update.valid.poke(false.B)
            dut.io.update.idx.poke(0.U)
            dut.io.update.taken.poke(false.B)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            predictTaken(expected = false) // 01
            train(taken = true)
            predictTaken(expected = true)  // 10
            train(taken = true, count = 8)
            predictTaken(expected = true)  // saturated at 11
            train(taken = false)
            predictTaken(expected = true)  // 11 -> 10
            train(taken = false)
            predictTaken(expected = false) // 10 -> 01
            train(taken = false, count = 8)
            predictTaken(expected = false) // saturated at 00
            train(taken = true)
            predictTaken(expected = false) // 00 -> 01
            train(taken = true)
            predictTaken(expected = true)  // 01 -> 10
        }
    }

    "BreezePHT should share state for PC and GHR pairs with the same xor index" in {
        simulate(new BreezePHT(vlen = 64, ghrLength = GhrLength)) { dut =>
            val pcA = 0x100
            val ghrA = 0x3
            val idx = ((pcA >> 2) & IndexMask) ^ ghrA
            val pcB = 0x120
            val pcBIdx = (pcB >> 2) & IndexMask
            val ghrB = pcBIdx ^ idx

            dut.io.predict.valid.poke(false.B)
            dut.io.predict.pc.poke(0.U)
            dut.io.predict.ghr.poke(0.U)
            dut.io.update.valid.poke(false.B)
            dut.io.update.idx.poke(0.U)
            dut.io.update.taken.poke(false.B)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            dut.io.update.valid.poke(true.B)
            dut.io.update.idx.poke(idx.U)
            dut.io.update.taken.poke(true.B)
            dut.clock.step(1)
            dut.io.update.valid.poke(false.B)

            dut.io.predict.valid.poke(true.B)
            dut.io.predict.pc.poke(pcB.U)
            dut.io.predict.ghr.poke(ghrB.U)
            dut.io.resp.idx.expect(idx.U)
            dut.io.resp.taken.expect(true.B)
        }
    }
}

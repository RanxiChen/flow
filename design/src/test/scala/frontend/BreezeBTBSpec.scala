package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.interface.FrontendPredType
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeBTBSpec extends AnyFreeSpec with Matchers with ChiselSim {
    "BreezeBTB should miss after reset and return updated metadata" in {
        simulate(new BreezeBTB(vlen = 64, entryNum = 4)) { dut =>
            def lookup(pc: BigInt): Unit = dut.io.lookup.pc.poke(pc.U)
            def update(pc: BigInt, target: BigInt, predType: FrontendPredType.Type, taken: Boolean): Unit = {
                dut.io.update.valid.poke(true.B)
                dut.io.update.pc.poke(pc.U)
                dut.io.update.target.poke(target.U)
                dut.io.update.predType.poke(predType)
                dut.io.update.taken.poke(taken.B)
                dut.clock.step(1)
                dut.io.update.valid.poke(false.B)
            }

            dut.io.lookup.pc.poke(0.U)
            dut.io.update.valid.poke(false.B)
            dut.io.update.pc.poke(0.U)
            dut.io.update.target.poke(0.U)
            dut.io.update.predType.poke(FrontendPredType.NONE)
            dut.io.update.taken.poke(false.B)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            lookup(0x100)
            dut.io.resp.hit.expect(false.B)
            dut.io.resp.predType.expect(FrontendPredType.NONE)

            update(0x100, 0x80, FrontendPredType.BR, taken = true)
            lookup(0x100)
            dut.io.resp.hit.expect(true.B)
            dut.io.resp.target.expect(0x80.U)
            dut.io.resp.predType.expect(FrontendPredType.BR)
            dut.io.resp.taken.expect(true.B)

            // An update to the same full PC tag must modify the existing entry.
            update(0x100, 0x200, FrontendPredType.JAL, taken = true)
            lookup(0x100)
            dut.io.resp.hit.expect(true.B)
            dut.io.resp.target.expect(0x200.U)
            dut.io.resp.predType.expect(FrontendPredType.JAL)

            // A PC with the same low index bits but a different full tag must miss.
            lookup(0x100000100L)
            dut.io.resp.hit.expect(false.B)
        }
    }

    "BreezeBTB should replace full entries in round-robin order" in {
        simulate(new BreezeBTB(vlen = 64, entryNum = 2)) { dut =>
            def update(pc: BigInt, target: BigInt): Unit = {
                dut.io.update.valid.poke(true.B)
                dut.io.update.pc.poke(pc.U)
                dut.io.update.target.poke(target.U)
                dut.io.update.predType.poke(FrontendPredType.BR)
                dut.io.update.taken.poke(true.B)
                dut.clock.step(1)
                dut.io.update.valid.poke(false.B)
            }
            def expectLookup(pc: BigInt, hit: Boolean, target: BigInt = 0): Unit = {
                dut.io.lookup.pc.poke(pc.U)
                dut.io.resp.hit.expect(hit.B)
                if (hit) dut.io.resp.target.expect(target.U)
            }

            dut.io.lookup.pc.poke(0.U)
            dut.io.update.valid.poke(false.B)
            dut.io.update.pc.poke(0.U)
            dut.io.update.target.poke(0.U)
            dut.io.update.predType.poke(FrontendPredType.NONE)
            dut.io.update.taken.poke(false.B)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            update(0x100, 0x80)
            update(0x104, 0x84)
            expectLookup(0x100, hit = true, target = 0x80)
            expectLookup(0x104, hit = true, target = 0x84)

            update(0x108, 0x88)
            expectLookup(0x100, hit = false)
            expectLookup(0x104, hit = true, target = 0x84)
            expectLookup(0x108, hit = true, target = 0x88)

            update(0x10c, 0x8c)
            expectLookup(0x104, hit = false)
            expectLookup(0x108, hit = true, target = 0x88)
            expectLookup(0x10c, hit = true, target = 0x8c)
        }
    }
}

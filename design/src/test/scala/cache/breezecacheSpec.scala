package flow.cache

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import flow.config.DefaultICacheConfig

class BreezeCacheSpec extends AnyFreeSpec with Matchers with ChiselSim {
    val cfg = DefaultICacheConfig()
    // 一些辅助函数
    class refCache(val cacheConfig: DefaultICacheConfig){
        ???
    }
    def sendCacheReq(dut: BreezeCache, vaddr: BigInt): Unit = {
        dut.io.dreq.valid.poke(true.B)
        dut.io.dreq.bits.vaddr.poke(vaddr.U)
        println(s"[INFO] Sending request for vaddr: 0x${vaddr.toString(16)}")
    }
    "BreezeCache should miss after reset" in {
        simulate(new BreezeCache(cfg, enabledebug = true)){dut =>
            var cycle_count = 0
            // defaultly, chiseltest will auto reset the dut, so we can directly check the initial state
            sendCacheReq(dut, 0x0)
            println(s"[INFO] cycle 0")
            println(s"[INFO] cycle ${cycle_count} , s0 should valid")
            assert(dut.io.debug.get.s0_valid.peek().litToBoolean, "debug.s0_valid should be asserted after sendCacheReq")
            assert(dut.io.debug.get.s0_vaddr.peek().litValue == 0x0, "debug.s0_vaddr should match the requested address")
            dut.clock.step(1)
            cycle_count += 1
            println(s"[INFO] cycle 1")
            println(s"[INFO] cycle ${cycle_count} , s1 should valid")
            assert(dut.io.debug.get.s1_valid.peek().litToBoolean, "debug.s1_valid should be asserted one cycle after sendCacheReq")
            assert(dut.io.debug.get.s1_vaddr.peek().litValue == 0x0, "debug.s1_vaddr should match the requested address")
            println(s"[INFO] s1_tag_hit = 0x${dut.io.debug.get.s1_tag_hit.peek().litValue.toString(16)}")
            println(s"[INFO] s1_hit = ${dut.io.debug.get.s1_hit.peek().litToBoolean}")
        }
    }

    
}

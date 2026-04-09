package flow.cache

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import flow.config.DefaultICacheConfig

class BreezePLRUSpec extends AnyFreeSpec with Matchers with ChiselSim {
    class BreezePLRUWrapper extends Module {
        val io = IO(new Bundle{
            val valid_vec = Input(UInt(4.W))
            val plru_vec = Input(UInt(3.W))
            val new_valid_vec = Output(UInt(4.W))
            val new_plru_vec = Output(UInt(3.W))
            val wb_en = Output(UInt(4.W))
        })
        val (new_valid_vec, new_plru_vec,wb_en_OH) = BreezePLRU.replace_way_select(io.valid_vec, io.plru_vec)
        io.new_valid_vec := new_valid_vec
        io.new_plru_vec := new_plru_vec
        io.wb_en := wb_en_OH
    }
    /*
    "PLRU should select the first invalid way if there is any" in {
        simulate(new BreezePLRUWrapper){dut =>
            //测试当valid_vec = 0b1011时，应该选择way 2进行替换
            dut.io.valid_vec.poke("b1011".U)
            dut.io.plru_vec.poke("b000".U) // plru_vec的值在有invalid way时不应该影响结果
            dut.io.new_valid_vec.expect("b1111".U) // 替换之后所有way都应该是valid的
            dut.io.wb_en.expect("b0100".U) // 应该选择way 2进行写回
        }
    }
     "PLRU should select the correct way to replace when all ways are valid" in {
        simulate(new BreezePLRUWrapper){dut =>
            //测试当valid_vec = 0b1111且plru_vec = 0b000时，应该选择way 0进行替换
            dut.io.valid_vec.poke("b1111".U)
            dut.io.plru_vec.poke("b000".U)
            dut.clock.step(1)
            dut.io.wb_en.expect("b0001".U) // 应该选择way 0进行写回

            //测试当valid_vec = 0b1111且plru_vec = 0b001时，应该选择way 1进行替换
            dut.io.valid_vec.poke("b1111".U)
            dut.io.plru_vec.poke("b001".U)
            dut.clock.step(1)
            dut.io.wb_en.expect("b0010".U) // 应该选择way 1进行写回

            //测试当valid_vec = 0b1111且plru_vec = 0b010时，应该选择way 2进行替换
            dut.io.valid_vec.poke("b1111".U)
            dut.io.plru_vec.poke("b010".U)
            dut.clock.step(1)
            dut.io.wb_en.expect("b0100".U) // 应该选择way 2进行写回

             //测试当valid_vec = 0b1111且plru_vec = 0b011时，应该选择way 3进行替换
             dut.io.valid_vec.poke("b1111".U)
             dut.io.plru_vec.poke("b011".U)
             dut.clock.step(1)
             dut.io.wb_en.expect("b1000".U) // 应该选择way 3进行写回
        }
    }*/
}

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
            println(s"[INFO] cycle ${cycle_count} , checking miss backpressure: s0 ready should be blocked")
            dut.io.debug.get.s0_ready.expect(false.B)
            dut.clock.step(1)
            cycle_count += 1
            println(s"[INFO] ==================== enter s2 check ====================")
            println(s"[INFO] cycle ${cycle_count} , only s2 should be valid")
            dut.io.debug.get.s0_valid.expect(false.B)
            dut.io.debug.get.s1_valid.expect(false.B)
            dut.io.debug.get.s2_valid.expect(true.B)
            dut.io.debug.get.s2_vaddr.expect(0x0.U)
        }
    }

    
}

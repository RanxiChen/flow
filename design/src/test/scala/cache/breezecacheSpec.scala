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
            var s2Cycle = 0
            val refillLine = BigInt("1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100", 16)
            // defaultly, chiseltest will auto reset the dut, so we can directly check the initial state
            dut.io.drsp.ready.poke(true.B)
            dut.io.next_level_rsp.vld.poke(false.B)
            dut.io.next_level_rsp.data.poke(0.U)
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
            s2Cycle = 1
            println(s"[INFO] ==================== enter s2 check ====================")
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , only s2 should be valid")
            dut.io.debug.get.s0_valid.expect(false.B)
            dut.io.debug.get.s1_valid.expect(false.B)
            dut.io.debug.get.s2_valid.expect(true.B)
            dut.io.debug.get.s2_vaddr.expect(0x0.U)
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , checking request pulse and wait state")
            dut.io.next_level_req.req.expect(true.B)
            dut.io.debug.get.s2_req_pulse_done.expect(false.B)
            dut.io.debug.get.wait_rsp.expect(false.B)
            dut.clock.step(1)
            cycle_count += 1
            s2Cycle += 1
            println(s"[INFO] ==================== enter post-s2 pulse check ====================")
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , req should be invalid, wait_rsp high, pulse_done high")
            dut.io.next_level_req.req.expect(false.B)
            dut.io.debug.get.s2_req_pulse_done.expect(true.B)
            dut.io.debug.get.wait_rsp.expect(true.B)
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , waiting one empty cycle before manual refill response")
            dut.io.next_level_rsp.vld.expect(false.B)
            dut.io.debug.get.s1_valid.expect(false.B)
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , before sending 0x4 later, s1_valid = ${dut.io.debug.get.s1_valid.peek().litToBoolean}")
            dut.clock.step(1)
            cycle_count += 1
            s2Cycle += 1
            println(s"[INFO] ==================== enter refill response check ====================")
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , manually return one whole 32B cache line from next level")
            dut.io.next_level_rsp.vld.poke(true.B)
            dut.io.next_level_rsp.data.poke(refillLine.U)
            println(s"[INFO] refill line = 0x${refillLine.toString(16)}")
            dut.io.debug.get.wait_rsp.expect(true.B)
            val dataArrayWe = dut.io.debug.get.data_array_we.peek().litValue
            val tagArrayWe = dut.io.debug.get.tag_array_we.peek().litValue
            assert(dataArrayWe != 0, "debug.data_array_we should not be all zero when refill response is valid")
            assert(tagArrayWe != 0, "debug.tag_array_we should not be all zero when refill response is valid")
            println(s"[INFO] cycle ${cycle_count} , data_array_we = 0x${dataArrayWe.toString(16)}")
            println(s"[INFO] cycle ${cycle_count} , tag_array_we = 0x${tagArrayWe.toString(16)}")
            val dataArrayWeIdx = (0 until cfg.ICACHE_WAY_NUM).filter(i => ((dataArrayWe >> i) & 1) == 1)
            val tagArrayWeIdx = (0 until cfg.ICACHE_WAY_NUM).filter(i => ((tagArrayWe >> i) & 1) == 1)
            println(s"[INFO] cycle ${cycle_count} , data_array_we active ways = ${dataArrayWeIdx.mkString(",")}")
            println(s"[INFO] cycle ${cycle_count} , tag_array_we active ways = ${tagArrayWeIdx.mkString(",")}")
            dut.clock.step(1)
            cycle_count += 1
            s2Cycle += 1
            println(s"[INFO] ==================== enter refill done check ====================")
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , drive next_level_rsp.vld low after one full cycle response")
            dut.io.next_level_rsp.vld.poke(false.B)
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , refill_done and s2_done should be high after one-cycle response")
            dut.io.debug.get.wait_rsp.expect(false.B)
            dut.io.debug.get.s2_refill_done.expect(true.B)
            dut.io.debug.get.s2_done.expect(true.B)
            dut.io.debug.get.s2_req_pulse_done.expect(true.B)
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , send a new request 0x4 while s2_done is high")
            dut.io.dreq.valid.poke(true.B)
            dut.io.dreq.bits.vaddr.poke(0x4.U)
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , check 0x4 enters cache while miss result is returned")
            dut.io.debug.get.s0_valid.expect(true.B)
            dut.io.debug.get.s1_valid.expect(false.B)
            dut.io.debug.get.s2_valid.expect(true.B)
            println(s"[INFO] cycle ${cycle_count} , s2 cycle ${s2Cycle} , drsp should respond")
            dut.io.drsp.valid.expect(true.B)
            dut.io.drsp.bits.data.expect("h03020100".U)
            dut.clock.step(1)
            cycle_count += 1
            println(s"[INFO] ==================== enter post-refill hit pipeline check ====================")
            println(s"[INFO] cycle ${cycle_count} , send next request 0x8 and check hit pipeline state")
            dut.io.dreq.valid.poke(true.B)
            dut.io.dreq.bits.vaddr.poke(0x8.U)
            val s1Meta = dut.io.debug.get.s1_meta.peek().litValue
            val s1ValidVec = s1Meta & 0xf
            val s1Plru = (s1Meta >> 4) & 0x7
            println(s"[INFO] cycle ${cycle_count} , cycle 6 state before expect: dreq.ready=${dut.io.dreq.ready.peek().litToBoolean}, s0_valid=${dut.io.debug.get.s0_valid.peek().litToBoolean}, s1_valid=${dut.io.debug.get.s1_valid.peek().litToBoolean}, s1_vaddr=0x${dut.io.debug.get.s1_vaddr.peek().litValue.toString(16)}, s1_tag_hit=0x${dut.io.debug.get.s1_tag_hit.peek().litValue.toString(16)}, s1_hit=${dut.io.debug.get.s1_hit.peek().litToBoolean}, s2_valid=${dut.io.debug.get.s2_valid.peek().litToBoolean}, s2_done=${dut.io.debug.get.s2_done.peek().litToBoolean}")
            println(s"[INFO] cycle ${cycle_count} , cycle 6 meta state: s1_meta=0x${s1Meta.toString(16)}, valid_vec=0x${s1ValidVec.toString(16)}, plru=0x${s1Plru.toString(16)}")
            dut.io.debug.get.s0_valid.expect(true.B)
            dut.io.debug.get.s1_valid.expect(true.B)
            dut.io.debug.get.s2_valid.expect(false.B)
            dut.io.drsp.valid.expect(true.B)
            dut.io.drsp.bits.data.expect("h07060504".U)
        }
    }

    "BreezeCache should follow default flow after reset" in {
        simulate(new BreezeCache(cfg, enabledebug = true)){dut =>
            dut.io.dreq.valid.poke(false.B)
            dut.io.dreq.bits.vaddr.poke(0.U)
            dut.io.drsp.ready.poke(true.B)
            dut.io.next_level_rsp.vld.poke(false.B)
            dut.io.next_level_rsp.data.poke(0.U)

            println("hello world")
        }
    }

    
}

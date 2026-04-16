package flow.frontend

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable.ArrayDeque

case class CapturedFetchPacket(
    pc: BigInt,
    inst: BigInt,
    predType: BigInt,
    predTaken: Boolean,
    predPc: BigInt
)

class BreezeFrontendSpec extends AnyFreeSpec with Matchers with ChiselSim {
    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        val imm12 = imm & 0xfff
        BigInt((imm12 << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | 0x13)
    }

    private def buildRefillLine(words: Seq[BigInt]): BigInt = {
        words.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (word, idx)) =>
            acc | ((word & BigInt("ffffffff", 16)) << (idx * 32))
        }
    }

    class VirtualFetchBuffer(val capacity: Int = 5) {
        private val queue = ArrayDeque.empty[CapturedFetchPacket]

        def size: Int = queue.size

        def freeEntries: Int = capacity - queue.size

        def canAccept3: Boolean = freeEntries >= 3

        def dequeueOne(): Option[CapturedFetchPacket] = {
            if (queue.nonEmpty) Some(queue.removeHead()) else None
        }

        def peekAll: Seq[CapturedFetchPacket] = queue.toSeq
    }

    "BreezeFrontend should correctly work after reset" in {
        simulate(new BreezeFrontend(enabledebug = true)){dut =>
            dut.io.resetAddr.poke(0x0.U)
            dut.io.beRedirect.valid.poke(false.B)
            dut.io.beRedirect.target.poke("hdeadbeef".U)
            dut.io.fetchBuffer.canAccept3.poke(false.B)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke("hdeadbeef".U)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            println(f"[INFO] after reset: s1_pcReg = 0x${dut.io.debug.get.s1_pcReg.peek().litValue}%x")
            println(s"[INFO] after reset: s1_valid = ${dut.io.debug.get.s1_valid.peek().litToBoolean}")
            dut.io.debug.get.s1_pcReg.expect(0x0.U)
            dut.io.fetchBuffer.valid.expect(false.B)

            dut.reset.poke(false.B)
            dut.io.fetchBuffer.canAccept3.poke(true.B)
        }
    }

    "BreezeFrontend should follow default flow after reset" in {
        simulate(new BreezeFrontend(enabledebug = true)){dut =>
            val debug = dut.io.debug.get
            val refillDelayCycles = 6
            val refillInstWords = Seq.tabulate(8)(i => encodeAddi(rd = i + 1, rs1 = 0, imm = i + 1))
            val refillLineData = buildRefillLine(refillInstWords)

            // ==================== cycle 0 ====================
            // 先建立这个用例的默认输入环境：
            // 1. fetch buffer 先默认可接收，避免前端因为后端背压而不工作
            // 2. reset 地址固定为 0，作为复位后的起始 PC
            // 3. 后端重定向关闭，只观察默认顺序取指流程
            // 4. 下一级存储当前不返回数据，先看前端在“无反馈”时的初始状态
            dut.io.fetchBuffer.canAccept3.poke(true.B)
            dut.io.resetAddr.poke(0x0.U)
            dut.io.beRedirect.valid.poke(false.B)
            dut.io.beRedirect.target.poke(0x0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0x0.U)

            // 显式把 reset 拉高一个拍，观察 reset 后的第一个有效工作拍。
            dut.reset.poke(true.B)
            dut.clock.step(1)

            // cycle 0:
            // 1. 前端 s1 已经被 reset 初始化到 resetAddr，并保持 valid
            // 2. cache 的 s1 还没有接住请求，因此 s1_valid 应为低
            // 3. 这一拍 cache 的 s0 入口 ready，首个请求在本拍完成 fire
            println(f"[INFO] default-flow cycle 0: s1_pcReg = 0x${debug.s1_pcReg.peek().litValue}%x")
            println(s"[INFO] default-flow cycle 0: s1_valid = ${debug.s1_valid.peek().litToBoolean}")
            debug.s1_pcReg.expect(0x0.U)
            debug.s1_valid.expect(true.B)
            debug.cache_s1_valid.expect(false.B)
            debug.cache_s0_ready.expect(true.B)
            debug.dreq_fire.expect(true.B)
            dut.io.fetchBuffer.valid.expect(false.B)
            debug.s3_valid.expect(false.B)

            // ==================== cycle 1 ====================
            // 这里把 reset 拉回正常值，进入下一拍的默认顺序流观察。
            dut.reset.poke(false.B)
            dut.clock.step(1)

            println(f"[INFO] default-flow cycle 1: s1_pcReg = 0x${debug.s1_pcReg.peek().litValue}%x")
            println(s"[INFO] default-flow cycle 1: s1_valid = ${debug.s1_valid.peek().litToBoolean}")

            // cycle 1:
            // 1. 首拍请求已经在 cycle 0 被 cache 接收，因此前端 s1_pc 已经推进到 4
            // 2. cache 的 s0 入口在这一拍被阻塞，不再接受新请求
            // 3. 上一拍接收的请求已经进入 cache s1，并在这一拍表现为 miss
            debug.s1_pcReg.expect(0x4.U)
            debug.s1_valid.expect(true.B)
            debug.dreq_valid.expect(true.B)
            debug.cache_s0_ready.expect(false.B)
            debug.dreq_fire.expect(false.B)
            debug.cache_s1_valid.expect(true.B)
            debug.cache_s1_hit.expect(false.B)
            dut.io.fetchBuffer.valid.expect(false.B)
            debug.s3_valid.expect(false.B)

            // ==================== poll nextLevelReq ====================
            // 从这里开始轮询 miss 请求接口，直到 cache 真正向下一级发出请求。
            var observedMissReq = false
            var waitCycles = 0
            while (!observedMissReq) {
                println(
                  f"[INFO] poll miss req: nextLevelReq.req=${dut.io.nextLevelReq.req.peek().litToBoolean} " +
                    f"paddr=0x${dut.io.nextLevelReq.paddr.peek().litValue}%x " +
                    f"s1_pcReg=0x${debug.s1_pcReg.peek().litValue}%x " +
                    f"s3_valid=${debug.s3_valid.peek().litToBoolean}"
                )
                dut.io.nextLevelRsp.vld.expect(false.B)
                dut.io.fetchBuffer.valid.expect(false.B)
                debug.s3_valid.expect(false.B)

                if (dut.io.nextLevelReq.req.peek().litToBoolean) {
                    observedMissReq = true
                    dut.io.nextLevelReq.paddr.expect(0x0.U)
                } else {
                    dut.clock.step(1)
                }
            }

            // ==================== fixed 6-cycle wait ====================
            // 看到 miss 请求以后，固定空等 6 拍，不返回任何 refill 数据。
            while (waitCycles < refillDelayCycles) {
                dut.clock.step(1)
                waitCycles += 1
                println(
                  f"[INFO] wait refill cycle $waitCycles: " +
                    f"nextLevelReq.req=${dut.io.nextLevelReq.req.peek().litToBoolean} " +
                    f"s3_valid=${debug.s3_valid.peek().litToBoolean}"
                )
                dut.io.nextLevelRsp.vld.expect(false.B)
                dut.io.fetchBuffer.valid.expect(false.B)
                debug.s3_valid.expect(false.B)
            }

            // ==================== single-cycle refill response ====================
            // 第 6 拍等待之后，用一拍送回完整 32B cache line。
            dut.io.nextLevelRsp.data.poke(refillLineData.U)
            dut.io.nextLevelRsp.vld.poke(true.B)
            dut.clock.step(1)

            println(
              f"[INFO] refill response accept cycle: " +
                f"s2_respValid=${debug.s2_respValid.peek().litToBoolean} " +
                f"s3_valid=${debug.s3_valid.peek().litToBoolean} " +
                f"fetchBuffer.valid=${dut.io.fetchBuffer.valid.peek().litToBoolean}"
            )
            dut.io.fetchBuffer.valid.expect(false.B)
            debug.s3_valid.expect(false.B)

            // ==================== cache response becomes visible to s2 ====================
            // 按照 BreezeCacheSpec 的时序，nextLevelRsp.vld 后一拍，cache 才把返回对前端可见。
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.clock.step(1)

            println(
              f"[INFO] s2 response visible cycle: " +
                f"s2_valid=${debug.s2_valid.peek().litToBoolean} " +
                f"s2_pcReg=0x${debug.s2_pcReg.peek().litValue}%x " +
                f"s2_respValid=${debug.s2_respValid.peek().litToBoolean} " +
                f"s1_valid=${debug.s1_valid.peek().litToBoolean} " +
                f"s1_pcReg=0x${debug.s1_pcReg.peek().litValue}%x " +
                f"dreq_fire=${debug.dreq_fire.peek().litToBoolean}"
            )
            debug.s2_respValid.expect(true.B)
            debug.s2_valid.expect(true.B)
            debug.s2_pcReg.expect(0x0.U)

            // ==================== first visible instruction after refill ====================
            // 再下一拍，0x0 进入 s3；同时 s2 挂着 0x4 且应当 hit，s1 推进到 0x8。
            dut.clock.step(1)

            println(
              f"[INFO] post-refill s3 cycle: " +
                f"s3_valid=${debug.s3_valid.peek().litToBoolean} " +
                f"s3_pcReg=0x${debug.s3_pcReg.peek().litValue}%x " +
                f"s2_valid=${debug.s2_valid.peek().litToBoolean} " +
                f"s2_pcReg=0x${debug.s2_pcReg.peek().litValue}%x " +
                f"s2_respValid=${debug.s2_respValid.peek().litToBoolean} " +
                f"s1_valid=${debug.s1_valid.peek().litToBoolean} " +
                f"s1_pcReg=0x${debug.s1_pcReg.peek().litValue}%x " +
                f"cache_s1_hit=${debug.cache_s1_hit.peek().litToBoolean} " +
                f"dreq_fire=${debug.dreq_fire.peek().litToBoolean} " +
                f"fetch_pc=0x${dut.io.fetchBuffer.bits.pc.peek().litValue}%x " +
                f"fetch_inst=0x${dut.io.fetchBuffer.bits.inst.peek().litValue}%x"
            )
            debug.s3_valid.expect(true.B)
            debug.s3_pcReg.expect(0x0.U)
            debug.s2_valid.expect(true.B)
            debug.s2_pcReg.expect(0x4.U)
            debug.s2_respValid.expect(true.B)
            debug.s1_valid.expect(true.B)
            debug.s1_pcReg.expect(0x8.U)
            debug.cache_s1_hit.expect(true.B)
            dut.io.fetchBuffer.valid.expect(true.B)
            dut.io.fetchBuffer.bits.pc.expect(0x0.U)
            dut.io.fetchBuffer.bits.inst.expect(refillInstWords.head.U)

            // ==================== next cycle should keep streaming ====================
            // 如果前一拍组合逻辑已经在尝试把 0x10 推进进来，那么这一拍 s1 应推进到 0x10。
            dut.clock.step(1)
            println(
              f"[INFO] stream-forward cycle: " +
                f"s1_valid=${debug.s1_valid.peek().litToBoolean} " +
                f"s1_pcReg=0x${debug.s1_pcReg.peek().litValue}%x " +
                f"s2_valid=${debug.s2_valid.peek().litToBoolean} " +
                f"s2_pcReg=0x${debug.s2_pcReg.peek().litValue}%x " +
                f"s3_valid=${debug.s3_valid.peek().litToBoolean}"
            )
            debug.s1_valid.expect(true.B)
            debug.s1_pcReg.expect(0x10.U)
        }
    }
}

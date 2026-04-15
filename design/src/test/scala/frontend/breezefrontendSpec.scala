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
        }
    }
}

class BreezeFrontendFE001Spec extends AnyFreeSpec with Matchers with ChiselSim {
    "BreezeFrontend FE-001 bug replication scenario" in {
        simulate(new BreezeFrontend(enabledebug = true)){dut =>
            val debug = dut.io.debug.get

            // ==================== cycle 0 ====================
            // 先建立这个用例的默认输入环境：
            // 1. fetch buffer 先默认可接收，避免前端因为后端背压而不工作
            // 2. reset 地址固定为 0，作为复位后的起始 PC
            // 3. 后端重定向关闭，只观察默认顺序取指流程
            // 4. 下一级存储当前不返回数据，先看前端在"无反馈"时的初始状态
            dut.io.fetchBuffer.canAccept3.poke(true.B)
            dut.io.resetAddr.poke(0x0.U)
            dut.io.beRedirect.valid.poke(false.B)
            dut.io.beRedirect.target.poke(0x0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0x0.U)

            // 显式把 reset 拉高一个拍，确保这里观察到的是"复位作用后的寄存器值"
            dut.reset.poke(true.B)
            dut.clock.step(1)

            // 这一拍仍处于 reset 作用后的状态，只检查 reset 是否把 s1 初始化到了 resetAddr。
            println(f"[INFO] default-flow cycle 0: s1_pcReg = 0x${debug.s1_pcReg.peek().litValue}%x")
            println(s"[INFO] default-flow cycle 0: s1_valid = ${debug.s1_valid.peek().litToBoolean}")
            println(
              f"[INFO] FE001 cycle 0 frontend: " +
                f"dreq_valid=${debug.dreq_valid.peek().litToBoolean} " +
                f"dreq_ready=${debug.dreq_ready.peek().litToBoolean} " +
                f"dreq_fire=${debug.dreq_fire.peek().litToBoolean} " +
                f"s2_valid=${debug.s2_valid.peek().litToBoolean} " +
                f"s2_pcReg=0x${debug.s2_pcReg.peek().litValue}%x " +
                f"s2_respValid=${debug.s2_respValid.peek().litToBoolean} " +
                f"s3_valid=${debug.s3_valid.peek().litToBoolean} " +
                f"s3_pcReg=0x${debug.s3_pcReg.peek().litValue}%x"
            )
            println(
              f"[INFO] FE001 cycle 0 cache: " +
                f"s0_valid=${debug.dreq_fire.peek().litToBoolean} " +
                f"s0_ready=${debug.cache_s0_ready.peek().litToBoolean} " +
                f"s1_valid=${debug.cache_s1_valid.peek().litToBoolean} " +
                f"s1_hit=${debug.cache_s1_hit.peek().litToBoolean} " +
                f"s2_valid=${debug.cache_s2_valid.peek().litToBoolean} " +
                f"s2_done=${debug.cache_s2_done.peek().litToBoolean}"
            )
            println(
              f"[INFO] FE001 cycle 0 inputs: " +
                f"fetchBuffer.canAccept3=${dut.io.fetchBuffer.canAccept3.peek().litToBoolean} " +
                f"fetchBuffer.valid=${dut.io.fetchBuffer.valid.peek().litToBoolean} " +
                f"beRedirect.valid=${dut.io.beRedirect.valid.peek().litToBoolean} " +
                f"nextLevelRsp.vld=${dut.io.nextLevelRsp.vld.peek().litToBoolean}"
            )
            debug.cache_s0_ready.expect(true.B)
            debug.dreq_fire.expect(true.B)

            // ==================== cycle 1 ====================
            // 这里把 reset 拉回正常值，表示从这个周期开始进入正常工作流。
            // 其他输入保持不变：fetch buffer 仍可接收、后端无重定向、下一级仍无返回。
            dut.reset.poke(false.B)
            dut.clock.step(1)

            // 这一拍是默认顺序流真正开始工作的第一拍：
            // 1. s1 仍然有效
            // 2. s1 的 PC 仍是 0
            // 3. fetch buffer 允许前进，因此 dreq.valid 会被拉高
            // 4. cache 在冷启动后的这个周期准备好接收请求，所以本拍 dreq.fire
            println(f"[INFO] FE001 cycle 1: s1_pcReg = 0x${debug.s1_pcReg.peek().litValue}%x")
            println(s"[INFO] FE001 cycle 1: s1_valid = ${debug.s1_valid.peek().litToBoolean}")
            println(
              f"[INFO] FE001 cycle 1 frontend: " +
                f"dreq_valid=${debug.dreq_valid.peek().litToBoolean} " +
                f"dreq_ready=${debug.dreq_ready.peek().litToBoolean} " +
                f"dreq_fire=${debug.dreq_fire.peek().litToBoolean} " +
                f"s2_valid=${debug.s2_valid.peek().litToBoolean} " +
                f"s2_pcReg=0x${debug.s2_pcReg.peek().litValue}%x " +
                f"s2_respValid=${debug.s2_respValid.peek().litToBoolean} " +
                f"s3_valid=${debug.s3_valid.peek().litToBoolean} " +
                f"s3_pcReg=0x${debug.s3_pcReg.peek().litValue}%x"
            )
            println(
              f"[INFO] FE001 cycle 1 cache: " +
                f"s0_valid=${debug.dreq_fire.peek().litToBoolean} " +
                f"s0_ready=${debug.cache_s0_ready.peek().litToBoolean} " +
                f"s1_valid=${debug.cache_s1_valid.peek().litToBoolean} " +
                f"s1_hit=${debug.cache_s1_hit.peek().litToBoolean} " +
                f"s2_valid=${debug.cache_s2_valid.peek().litToBoolean} " +
                f"s2_done=${debug.cache_s2_done.peek().litToBoolean}"
            )
            println(
              f"[INFO] FE001 cycle 1 inputs: " +
                f"fetchBuffer.canAccept3=${dut.io.fetchBuffer.canAccept3.peek().litToBoolean} " +
                f"fetchBuffer.valid=${dut.io.fetchBuffer.valid.peek().litToBoolean} " +
                f"beRedirect.valid=${dut.io.beRedirect.valid.peek().litToBoolean} " +
                f"nextLevelRsp.vld=${dut.io.nextLevelRsp.vld.peek().litToBoolean}"
            )
            debug.cache_s0_ready.expect(false.B)
            debug.cache_s1_valid.expect(true.B)
            debug.cache_s1_hit.expect(false.B)
        }
    }
}

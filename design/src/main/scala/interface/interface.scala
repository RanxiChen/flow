package interface

import chisel3._
import chisel3.util._

object FlowConst{
    val pc_addr_width = 64
}

// This file stores native interfaces.
/**
  * 这个接口负责连接cache到一个虚拟的内存系统，提供地址和数据的输入输出
  * 这个接口仅仅用于仿真环境，实际的硬件设计中会使用更复杂的接口（比如AXI或者Wishbone）
  * 应该使用split-event接口，地址和数据分开传输
  *
  * === Burst 传输约定 ===
  * 本接口实现了隐式的 burst 传输模式：
  *
  * 1. Cache 发送一个 cache line 的起始地址（通过 NativeMemIO.speak()）
  * 2. 内存系统自动返回该 cache line 的所有数据（通过多次 NativeMemIO.listen()）
  * 3. 对于 32 字节的 cache line，总线宽度 64-bit 时：
  *    - 发送 1 次地址请求（例如：0x1000）
  *    - 接收 4 次数据响应：
  *      * beat 0: addr 0x1000 的 8 字节
  *      * beat 1: addr 0x1008 的 8 字节
  *      * beat 2: addr 0x1010 的 8 字节
  *      * beat 3: addr 0x1018 的 8 字节
  *
  * 4. 地址自动递增规则：
  *    - 起始地址必须是 cache line 对齐的（低 5 位为 0）
  *    - 每个 beat 地址递增 8 字节（总线宽度）
  *    - 内存模型负责自动管理地址递增
  *
  * 5. 握手协议：
  *    - 每个 beat 的数据传输都遵循标准的 ready-valid 握手
  *    - Cache 通过 listen() 接收每个 beat，直到收齐完整的 cache line
  *
  * 注意：
  * - 这是简化的仿真接口，burst length 是隐式固定的（由 cache line 大小决定）
  * - 真实硬件接口（如 AXI）会显式包含 burst length、burst type 等信号
  * - 当前约定：32-byte cache line = 4 beats（4 × 8 bytes）
  */
class NativeReqIO(val addr_width: Int) extends Bundle {
    val addr = UInt(addr_width.W)
}

class NativeRespIO(val data_width: Int) extends Bundle {
    val data = UInt(data_width.W)
}

class ICacheReq extends Bundle{
    val addr = UInt(FlowConst.pc_addr_width.W)
}

class ICacheResp extends Bundle{
    val data = UInt(32.W)
}
//from cache
class ICacheIO extends Bundle{
    val req = Flipped(Decoupled(new ICacheReq))
    val resp = Decoupled(new ICacheResp)
    def initialize(): Unit = {
        req.ready := false.B
        resp.valid := false.B
        resp.bits.data := 0.U
    }
}
/**
  * Native Memory IO 接口 - 用于仿真环境的内存访问接口
  *
  * 这是一个简化的 split-transaction 内存接口，支持 burst 传输：
  * - req 通道：发送地址请求（使用 speak() 方法）
  * - resp 通道：接收数据响应（使用 listen() 方法）
  *
  * Burst 模式：一个地址请求 → 多个数据响应
  * 详见上方 NativeReqIO 类注释中的 "Burst 传输约定"
  */
class NativeMemIO extends Bundle {
    val req = Decoupled(new NativeReqIO(64))
    val resp = Flipped(Decoupled(new NativeRespIO(64)))
    def speak(addr: UInt): Bool = {
        var invokecount = 0
        if(invokecount == 0){
            val done = WireDefault(false.B)
            req.bits.addr := addr
            req.valid := true.B
            when(req.ready) {
                done := true.B
            }
            invokecount += 1
            done
        } else {
            throw new Exception("NativeMemIO.request can only be invoked once in one module")
        }
    }
    def listen():(Bool,UInt) = {
        var invokecount = 0
        if(invokecount == 0){
            resp.ready := true.B
            val done = WireDefault(false.B)
            val data = WireDefault(0.U)
            when(resp.fire){
                done := true.B
                data := resp.bits.data
            }
            invokecount += 1
            (done, data)
        } else {
            throw new Exception("NativeMemIO.listen can only be invoked once in one module")
        }
    }
    def initialize(): Unit = {
        req.valid := false.B
        req.bits.addr := 0.U
        resp.ready := false.B
    }
}

class Frontend_Instruction_Bunlde(val pc_width: Int = 64) extends Bundle{
    val inst = UInt(32.W)
    val pc = UInt(pc_width.W)
    val address_misaligned = Bool()
    val access_fault = Bool()
}
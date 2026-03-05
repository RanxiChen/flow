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
}
//from cache
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

package flow.interface

import chisel3._
import chisel3.util._
import flow.core._

object FlowConst{
    val pc_addr_width = 64
}

object BranchPredictConst {
    def phtIdxWidth(ghrLength: Int): Int = if (ghrLength > 0) ghrLength else 1
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

class BackendMemReq(val VLEN: Int = FlowConst.pc_addr_width) extends Bundle {
    val valid = Bool()
    val isWrite = Bool()
    val addr = UInt(VLEN.W)
    val wdata = UInt(64.W)
    val wmask = UInt(8.W)
}

class BackendMemResp extends Bundle {
    val valid = Bool()
    val data = UInt(64.W)
    val isWriteAck = Bool()
}

class BackendMemIO(val VLEN: Int = FlowConst.pc_addr_width) extends Bundle {
    val req = new BackendMemReq(VLEN)
    val rsp = Flipped(new BackendMemResp)
}

class ICacheReq extends Bundle{
    val addr = UInt(FlowConst.pc_addr_width.W)
}

class ICacheResp extends Bundle{
    val data = UInt(32.W)
}

class FrontendRedirectIO(val VLEN: Int = 64) extends Bundle {
    val valid = Bool()
    val flush = Bool()
    val cacheFlush = Bool()
    val target = UInt(VLEN.W)
}

object FrontendPredType extends ChiselEnum {
    val NONE, BR, JAL, JALR = Value
}

class FrontendPredInfo(val VLEN: Int = 64, val ghrLength: Int = 0) extends Bundle {
    val predType = FrontendPredType()
    val predTaken = Bool()
    val predPc = UInt(VLEN.W)
    val phtIdx = UInt(BranchPredictConst.phtIdxWidth(ghrLength).W)
}

class BreezePHTPredictReq(val vlen: Int, val ghrLength: Int) extends Bundle {
    val valid = Bool()
    val pc = UInt(vlen.W)
    val ghr = UInt(ghrLength.W)
}

class BreezePHTPredictResp(val ghrLength: Int) extends Bundle {
    val valid = Bool()
    val taken = Bool()
    val idx = UInt(ghrLength.W)
}

class BreezePHTUpdateReq(val ghrLength: Int) extends Bundle {
    val valid = Bool()
    val idx = UInt(ghrLength.W)
    val taken = Bool()
}

class BreezeGHRUpdateReq extends Bundle {
    val valid = Bool()
    val taken = Bool()
}

class BreezeBTBLookupReq(val vlen: Int) extends Bundle {
    val pc = UInt(vlen.W)
}

class BreezeBTBLookupResp(val vlen: Int) extends Bundle {
    val hit = Bool()
    val taken = Bool()
    val predType = FrontendPredType()
    val target = UInt(vlen.W)
}

class BreezeBTBUpdateReq(val vlen: Int) extends Bundle {
    val valid = Bool()
    val pc = UInt(vlen.W)
    val target = UInt(vlen.W)
    val predType = FrontendPredType()
    val taken = Bool()
}

class FrontendFetchBundle(val VLEN: Int = 64, val ghrLength: Int = 0) extends Bundle {
    val pc = UInt(VLEN.W)
    val inst = UInt(32.W)
    val pred = new FrontendPredInfo(VLEN, ghrLength)
}

class FrontendFetchBufferIO(val VLEN: Int = 64, val ghrLength: Int = 0) extends Bundle {
    val valid = Output(Bool())
    val bits = Output(new FrontendFetchBundle(VLEN, ghrLength))
    val canAccept3 = Input(Bool())
}

class BackendDebugIO(val VLEN: Int = 64) extends Bundle {
    val decodeValid = Output(Bool())
    val decodeInst = Output(UInt(32.W))
    val decodePc = Output(UInt(VLEN.W))
    val idExeValid = Output(Bool())
    val idExeInst = Output(UInt(32.W))
    val idExePc = Output(UInt(VLEN.W))
    val idExeRs1Addr = Output(UInt(5.W))
    val idExeRs2Addr = Output(UInt(5.W))
    val idExeSrc1 = Output(UInt(VLEN.W))
    val idExeSrc2 = Output(UInt(VLEN.W))
    val exeSrc1 = Output(UInt(VLEN.W))
    val exeSrc2 = Output(UInt(VLEN.W))
    val exeAluOut = Output(UInt(VLEN.W))
    val exeBruTaken = Output(Bool())
    val exeJumpAddr = Output(UInt(VLEN.W))
    val exeMemValid = Output(Bool())
    val exeMemPc = Output(UInt(VLEN.W))
    val exeMemData = Output(UInt(VLEN.W))
    val exeMemRdAddr = Output(UInt(5.W))
    val memWaitingResp = Output(Bool())
    val memWbValid = Output(Bool())
    val memWbPc = Output(UInt(VLEN.W))
    val memWbInst = Output(UInt(32.W))
    val wbData = Output(UInt(VLEN.W))
    val exeBypassRs1 = Output(UInt(VLEN.W))
    val exeBypassRs2 = Output(UInt(VLEN.W))
    val loadUseHazard = Output(Bool())
    val redirectValid = Output(Bool())
}

class TracePayload(val VLEN: Int = 64) extends Bundle {
    val valid = Bool()
    val pc = UInt(VLEN.W)
    val inst = UInt(32.W)
    val nextPc = UInt(VLEN.W)
    val estop = Bool()
    val rdWriteEn = Bool()
    val rdAddr = UInt(5.W)
    val rdData = UInt(VLEN.W)
    val memEn = Bool()
    val memIsWrite = Bool()
    val memAddr = UInt(VLEN.W)
    val memAlignedAddr = UInt(VLEN.W)
    val memRData = UInt(VLEN.W)
    val memWData = UInt(64.W)
    val memWMask = UInt(8.W)
}

class BreezeBackendIDEXE(val VLEN: Int = 64, val ghrLength: Int = 0) extends Bundle {
    val valid = Bool()
    val pc = UInt(VLEN.W)
    val inst = UInt(32.W)
    val illegal_inst = Bool()
    val pred = new FrontendPredInfo(VLEN, ghrLength)
    val ctrl = new EXE_Ctrl
    val estop = Bool()
    val rs1_addr = UInt(5.W)
    val rs2_addr = UInt(5.W)
    val rd_addr = UInt(5.W)
    val rs1_data = UInt(VLEN.W)
    val rs2_data = UInt(VLEN.W)
    val imm = UInt(VLEN.W)
    val src1 = UInt(VLEN.W)
    val src2 = UInt(VLEN.W)
}

class BreezeBackendEXEMEM(val VLEN: Int = 64, val ghrLength: Int = 0, val enableTandem: Boolean = false) extends Bundle {
    val valid = Bool()
    val pc = UInt(VLEN.W)
    val inst = UInt(32.W)
    val illegal_inst = Bool()
    val pred = new FrontendPredInfo(VLEN, ghrLength)
    val estop = Bool()
    val fencei = Bool()
    val data = UInt(VLEN.W)
    val rs2_data = UInt(VLEN.W)
    val mem_cmd = UInt(MEM_TYPE.width.W)
    val rd_addr = UInt(5.W)
    val rs1_addr = UInt(5.W)
    val csr_addr = UInt(12.W)
    val csr_cmd = UInt(CSR_CMD.width.W)
    val wb_en = Bool()
    val wb_sel = UInt(SEL_WB.width.W)
    val actual_taken = Bool()
    val actual_target = UInt(VLEN.W)
    val trace = if (enableTandem) Some(new TracePayload(VLEN)) else None
}

class BreezeBackendMEMWB(val VLEN: Int = 64, val enableTandem: Boolean = false) extends Bundle {
    val valid = Bool()
    val pc = UInt(VLEN.W)
    val inst = UInt(32.W)
    val illegal_inst = Bool()
    val load_addr_misaligned = Bool()
    val store_addr_misaligned = Bool()
    val estop = Bool()
    val wb_en = Bool()
    val wb_sel = UInt(SEL_WB.width.W)
    val rd_addr = UInt(5.W)
    val alu_data = UInt(VLEN.W)
    val mem_data = UInt(VLEN.W)
    val csr_data = UInt(VLEN.W)
    val trace = if (enableTandem) Some(new TracePayload(VLEN)) else None
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

class BreezeCacheReqIO(val VLEN:Int =64) extends Bundle{
    val vaddr = UInt(VLEN.W)
}

class BreezeCacheRespIO(val VLEN:Int = 64,val FETCH_WIDTH:Int = 32) extends Bundle{
    val data = UInt(FETCH_WIDTH.W)
    val vaddr = UInt(VLEN.W)
}
/**
  * L1 ICache当miss的时候，向下级cache发出请求的接口
  * 默认方向是以ICache的视角
  *
  */
class L1CacheMissReqIO(val PLEN:Int = 64) extends Bundle{
    val paddr = Output(UInt(PLEN.W))
    val req = Output(Bool())
}

/**
  * L1 ICache miss的时候，下级向cache的返回接口
  * 每次下一级向l1 icache返回一个cache line位宽的数据
  * 默认是32byte = 32 * 8bit = 256 bits的cache line
  */

class L1CacheMissRespIO(val ICACHE_LINE_WIDTH:Int = 256) extends Bundle {
    val data = Input(UInt(ICACHE_LINE_WIDTH.W))
    val vld = Input(Bool())
}

class FASECoreIO() extends Bundle {
    val instruction = Input(UInt(32.W))
    val inst_ready = Output(Bool())
    val inst_valid = Input(Bool())
}

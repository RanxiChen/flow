package flow.frontend

import chisel3._
import chisel3.util._

import flow.cache.BreezeCache
import flow.config.BreezeFrontendConfig
import flow.interface._
import _root_.circt.stage.ChiselStage

class MiniDecode(val vlen: Int = 64) extends Module {
    val io = IO(new Bundle {
        val pc = Input(UInt(vlen.W))
        val inst = Input(UInt(32.W))
        val predType = Output(FrontendPredType())
        val predTaken = Output(Bool())
        val predPc = Output(UInt(vlen.W))
    })

    val isJal = io.inst(6, 0) === "b1101111".U
    val jalImm = Cat(
        Fill(vlen - 21, io.inst(31)),
        io.inst(31),
        io.inst(19, 12),
        io.inst(20),
        io.inst(30, 21),
        0.U(1.W)
    )

    io.predType := FrontendPredType.NONE
    io.predTaken := false.B
    io.predPc := 0.U

    when(isJal) {
        io.predType := FrontendPredType.JAL
        io.predTaken := true.B
        io.predPc := io.pc + jalImm
    }
}

/**
  * 使用 BreezeCache 的前端骨架。
  * 当前实现前端入口 PC 选择、cache 请求/返回，以及 s3 的快速预测输出。
  * 地址统一按虚拟地址处理。
  */
class BreezeFrontend(val cfg: BreezeFrontendConfig = BreezeFrontendConfig()) extends Module {
    val io = IO(new Bundle {
        val resetAddr = Input(UInt(cfg.VLEN.W))
        val beRedirect = Input(new FrontendRedirectIO(cfg.VLEN))
        val fetchBuffer = new FrontendFetchBufferIO(cfg.VLEN)
        val nextLevelReq = new L1CacheMissReqIO(cfg.cacheCfg.PLEN)
        val nextLevelRsp = new L1CacheMissRespIO(cfg.cacheCfg.ICACHE_LINE_WIDTH)
    })

    // ===== Module Instances =====
    val icache = Module(new BreezeCache(cfg.cacheCfg))
    val miniDecode = Module(new MiniDecode(cfg.VLEN))

    // ===== S1: Stage State =====
    val s1_pcReg = RegInit(0.U(cfg.VLEN.W))

    // ===== S2: Stage State =====
    val s2_validReg = RegInit(false.B)
    val s2_pcReg = RegInit(0.U(cfg.VLEN.W))

    // ===== S3: Stage State =====
    val s3_validReg = RegInit(false.B)
    val s3_pcReg = RegInit(0.U(cfg.VLEN.W))
    val s3_instReg = RegInit(0.U(32.W))

    // ===== S3: Mini Decode =====
    val s3_fastRedirectValid = Wire(Bool())
    val s3_fastRedirectTarget = Wire(UInt(cfg.VLEN.W))

    miniDecode.io.pc := s3_pcReg
    miniDecode.io.inst := s3_instReg
    s3_fastRedirectValid := s3_validReg && miniDecode.io.predTaken
    s3_fastRedirectTarget := miniDecode.io.predPc

    // ===== S0: Next PC Generation =====
    val s0_defaultNextPc = Wire(UInt(cfg.VLEN.W))
    val s0_backendRedirectSel = Wire(Bool())
    val s0_fastRedirectSel = Wire(Bool())
    val s0_fallThroughSel = Wire(Bool())
    val s0_canAdvance = Wire(Bool())
    val s0_nextPc = Wire(UInt(cfg.VLEN.W))
    val s1_valid = Wire(Bool())
    val s1_fire = Wire(Bool())
    val s2_respValid = Wire(Bool())

    s0_defaultNextPc := s1_pcReg + 4.U
    s0_backendRedirectSel := io.beRedirect.valid
    s0_fastRedirectSel := !s0_backendRedirectSel && s3_fastRedirectValid
    s0_fallThroughSel := !s0_backendRedirectSel && !s3_fastRedirectValid
    s0_canAdvance := io.fetchBuffer.canAccept3 && icache.io.drsp.valid
    s0_nextPc := Mux(
        reset.asBool,
        io.resetAddr,
        Mux1H(Seq(
            s0_backendRedirectSel -> io.beRedirect.target,
            s0_fastRedirectSel -> s3_fastRedirectTarget,
            s0_fallThroughSel -> s0_defaultNextPc
        ))
    )

    // ===== S1: Register Update =====
    s1_valid := io.fetchBuffer.canAccept3 && !s2_validReg
    s1_fire := icache.io.dreq.fire

    when(reset.asBool) {
        s1_pcReg := io.resetAddr
    }.elsewhen(s0_canAdvance) {
        s1_pcReg := s0_nextPc
    }

    // ===== S1: Cache Request =====
    icache.io.dreq.valid := s1_valid
    icache.io.dreq.bits.vaddr := s1_pcReg

    // ===== S2: Cache Request Tracking =====
    s2_respValid := icache.io.drsp.valid

    when(icache.io.dreq.fire) {
        s2_validReg := true.B
        s2_pcReg := s1_pcReg
    }.elsewhen(s2_respValid) {
        s2_validReg := false.B
    }

    // ===== S2: Cache Response =====
    icache.io.drsp.ready := true.B

    // ===== S3: Cache Response Registers =====
    s3_validReg := false.B
    when(s2_respValid) {
        s3_validReg := true.B
        s3_pcReg := s2_pcReg
        s3_instReg := icache.io.drsp.bits.data
    }

    // ===== S3: Fetch Buffer Output =====
    io.fetchBuffer.valid := s3_validReg
    io.fetchBuffer.bits.pc := s3_pcReg
    io.fetchBuffer.bits.inst := s3_instReg
    io.fetchBuffer.bits.pred.predType := miniDecode.io.predType
    io.fetchBuffer.bits.pred.predTaken := miniDecode.io.predTaken
    io.fetchBuffer.bits.pred.predPc := miniDecode.io.predPc

    // ===== Cache Miss Interface =====
    icache.io.next_level_req <> io.nextLevelReq
    icache.io.next_level_rsp <> io.nextLevelRsp
}

object FireBreezeFrontend extends App {
    ChiselStage.emitSystemVerilogFile(
        new BreezeFrontend(),
        Array("--target-dir", "build"),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "-default-layer-specialization=enable"
        )
    )
}

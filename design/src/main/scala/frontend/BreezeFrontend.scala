package flow.frontend

import chisel3._
import chisel3.util._

import flow.cache.BreezeCache
import flow.config._
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
    val isJalr = io.inst(6, 0) === "b1100111".U && io.inst(14, 12) === 0.U
    val isBranch = io.inst(6, 0) === "b1100011".U
    val jalImm = Cat(
        Fill(vlen - 21, io.inst(31)),
        io.inst(31),
        io.inst(19, 12),
        io.inst(20),
        io.inst(30, 21),
        0.U(1.W)
    )
    val branchImm = Cat(
        Fill(vlen - 13, io.inst(31)),
        io.inst(31),
        io.inst(7),
        io.inst(30, 25),
        io.inst(11, 8),
        0.U(1.W)
    )

    io.predType := FrontendPredType.NONE
    io.predTaken := false.B
    io.predPc := 0.U

    when(isJal) {
        io.predType := FrontendPredType.JAL
        io.predTaken := true.B
        io.predPc := io.pc + jalImm
    }.elsewhen(isBranch) {
        io.predType := FrontendPredType.BR
        io.predPc := io.pc + branchImm
    }.elsewhen(isJalr) {
        io.predType := FrontendPredType.JALR
    }
}

class BreezeFrontendDebugIO(vlen: Int) extends Bundle {
    val s1_pcReg = Output(UInt(vlen.W))
    val s1_valid = Output(Bool())
    val dreq_valid = Output(Bool())
    val dreq_ready = Output(Bool())
    val dreq_fire = Output(Bool())
    val s2_pcReg = Output(UInt(vlen.W))
    val s2_valid = Output(Bool())
    val s2_respValid = Output(Bool())
    val cache_drsp_valid = Output(Bool())
    val cache_drsp_vaddr = Output(UInt(vlen.W))
    val s3_pcReg = Output(UInt(vlen.W))
    val s3_valid = Output(Bool())
    val cache_s0_ready = Output(Bool())
    val cache_s1_valid = Output(Bool())
    val cache_s1_hit = Output(Bool())
    val cache_s2_valid = Output(Bool())
    val cache_s2_done = Output(Bool())
}

/**
  * 使用 BreezeCache 的前端骨架。
  * 当前实现前端入口 PC 选择、cache 请求/返回，以及 s3 的快速预测输出。
  * 地址统一按虚拟地址处理。
  */
class BreezeFrontend(val cfg: BreezeFrontendConfig = BreezeFrontendConfig(), val enabledebug: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val resetAddr = Input(UInt(cfg.VLEN.W))
        val beRedirect = Input(new FrontendRedirectIO(cfg.VLEN))
        val btbUpdate = Input(new BreezeBTBUpdateReq(cfg.VLEN))
        val phtUpdate = Input(new BreezePHTUpdateReq(cfg.branchPredCfg.ghrLength.max(1)))
        val ghrUpdate = Input(new BreezeGHRUpdateReq)
        val fetchBuffer = new FrontendFetchBufferIO(cfg.VLEN, cfg.branchPredCfg.ghrLength)
        val nextLevelReq = new L1CacheMissReqIO(cfg.cacheCfg.PLEN)
        val nextLevelRsp = new L1CacheMissRespIO(cfg.cacheCfg.ICACHE_LINE_WIDTH)
        val debug = if (enabledebug) Some(new BreezeFrontendDebugIO(cfg.VLEN)) else None
    })

    // ===== Module Instances =====
    val icache = Module(new BreezeCache(cfg.cacheCfg, enabledebug = enabledebug))
    val isGShare = cfg.branchPredCfg.kind == FrontendBranchPredictorKind.GShare

    // ===== GShare Optional State =====
    val miniDecode = if (isGShare) Some(Module(new MiniDecode(cfg.VLEN))) else None
    val ghrReg = if (isGShare) Some(RegInit(0.U(cfg.branchPredCfg.ghrLength.W))) else None
    val btb = if (isGShare) Some(Module(new BreezeBTB(cfg.VLEN, cfg.branchPredCfg.btbEntryNum))) else None
    val pht = if (isGShare) Some(Module(new BreezePHT(cfg.VLEN, cfg.branchPredCfg.ghrLength))) else None

    // ===== S1: Stage State =====
    val s1_validReg = RegInit(true.B)
    val s1_pcReg = RegInit(0.U(cfg.VLEN.W))
    val s1_fire = Wire(Bool())
    val s1_predTaken = Wire(Bool())
    val s1_predPc = Wire(UInt(cfg.VLEN.W))
    val s1_predType = Wire(FrontendPredType())
    val s1_phtIdx = Wire(UInt(cfg.branchPredCfg.ghrLength.max(1).W))

    // ===== S2: Stage State =====
    val s2_validReg = RegInit(false.B)
    val s2_pcReg = RegInit(0.U(cfg.VLEN.W))

    // ===== GShare S2 Metadata =====
    val s2_ghrSnapshotReg = if (isGShare) Some(RegInit(0.U(cfg.branchPredCfg.ghrLength.W))) else None
    val s2_predTakenReg = if (isGShare) Some(RegInit(false.B)) else None
    val s2_predPcReg = if (isGShare) Some(RegInit(0.U(cfg.VLEN.W))) else None
    val s2_predTypeReg = if (isGShare) Some(RegInit(FrontendPredType.NONE)) else None
    val s2_phtIdxReg = if (isGShare) Some(RegInit(0.U(cfg.branchPredCfg.ghrLength.W))) else None

    // ===== S3: Stage State =====
    val s3_validReg = RegInit(false.B)
    val s3_pcReg = RegInit(0.U(cfg.VLEN.W))
    val s3_instReg = RegInit(0.U(32.W))

    // ===== GShare S3 Metadata =====
    val s3_ghrSnapshotReg = if (isGShare) Some(RegInit(0.U(cfg.branchPredCfg.ghrLength.W))) else None
    val s3_predTakenReg = if (isGShare) Some(RegInit(false.B)) else None
    val s3_predPcReg = if (isGShare) Some(RegInit(0.U(cfg.VLEN.W))) else None
    val s3_predTypeReg = if (isGShare) Some(RegInit(FrontendPredType.NONE)) else None
    val s3_phtIdxReg = if (isGShare) Some(RegInit(0.U(cfg.branchPredCfg.ghrLength.W))) else None

    // ===== GShare Correction Wires =====
    val s3_fastRedirectValid = Wire(Bool())
    val s3_fastRedirectTarget = Wire(UInt(cfg.VLEN.W))
    val s3_finalPredTaken = Wire(Bool())
    val s3_finalPredPc = Wire(UInt(cfg.VLEN.W))
    val s3_finalPredType = Wire(FrontendPredType())

    s3_fastRedirectValid := false.B
    s3_fastRedirectTarget := 0.U
    s3_finalPredTaken := false.B
    s3_finalPredPc := 0.U
    s3_finalPredType := FrontendPredType.NONE

    // ===== S0: Next PC Generation =====
    val s0_defaultNextPc = Wire(UInt(cfg.VLEN.W))
    val redirectValid = Wire(Bool())
    val redirectTarget = Wire(UInt(cfg.VLEN.W))
    val s0_fallThroughSel = Wire(Bool())
    val s0_nextPc = Wire(UInt(cfg.VLEN.W))
    val s2_respValid = Wire(Bool())

    s0_defaultNextPc := s1_pcReg + 4.U
    redirectValid := io.beRedirect.valid || s3_fastRedirectValid
    redirectTarget := Mux(io.beRedirect.valid, io.beRedirect.target, s3_fastRedirectTarget)
    s0_fallThroughSel := !redirectValid
    s0_nextPc := Mux(
        reset.asBool,
        io.resetAddr,
        Mux1H(Seq(
            redirectValid -> redirectTarget,
            s0_fallThroughSel -> s0_defaultNextPc
        ))
    )

    s1_predTaken := false.B
    s1_predPc := s1_pcReg + 4.U
    s1_predType := FrontendPredType.NONE
    s1_phtIdx := 0.U

    // ===== GShare S1 Prediction =====
    if (isGShare) {
        btb.get.io.lookup.pc := s1_pcReg
        btb.get.io.update := io.btbUpdate

        pht.get.io.predict.valid := s1_fire
        pht.get.io.predict.pc := s1_pcReg
        pht.get.io.predict.ghr := ghrReg.get
        pht.get.io.update := io.phtUpdate

        s1_phtIdx := pht.get.io.resp.idx

        when(btb.get.io.resp.hit) {
            s1_predType := btb.get.io.resp.predType
            switch(btb.get.io.resp.predType) {
                is(FrontendPredType.JAL) {
                    s1_predTaken := true.B
                    s1_predPc := btb.get.io.resp.target
                }
                is(FrontendPredType.JALR) {
                    s1_predTaken := true.B
                    s1_predPc := btb.get.io.resp.target
                }
                is(FrontendPredType.BR) {
                    s1_predTaken := pht.get.io.resp.taken
                    s1_predPc := Mux(pht.get.io.resp.taken, btb.get.io.resp.target, s1_pcReg + 4.U)
                }
            }
        }

        s0_defaultNextPc := s1_predPc
    }

    // ===== GShare Update Path =====
    s1_fire := icache.io.dreq.fire

    if (isGShare) {
        when(reset.asBool) {
            ghrReg.get := 0.U
        }.elsewhen(io.ghrUpdate.valid) {
            ghrReg.get := Cat(ghrReg.get(cfg.branchPredCfg.ghrLength - 2, 0), io.ghrUpdate.taken)
        }
    }

    // ===== S1: Register Update =====
    when(reset.asBool) {
        s1_validReg := true.B
        s1_pcReg := io.resetAddr
    }.elsewhen(redirectValid) {
        s1_pcReg := redirectTarget
    }.elsewhen(s1_fire) {
        s1_pcReg := s0_nextPc
    }

    // ===== S1: Cache Request =====
    icache.io.dreq.valid := s1_validReg && io.fetchBuffer.canAccept3
    icache.io.dreq.bits.vaddr := s1_pcReg

    // ===== S2: Cache Request Tracking =====
    s2_respValid := s2_validReg && icache.io.drsp.valid

    when(reset.asBool || redirectValid) {
        s2_validReg := false.B
        s2_pcReg := 0.U
        if (isGShare) {
            s2_ghrSnapshotReg.get := 0.U
            s2_predTakenReg.get := false.B
            s2_predPcReg.get := 0.U
            s2_predTypeReg.get := FrontendPredType.NONE
            s2_phtIdxReg.get := 0.U
        }
    }.elsewhen(icache.io.dreq.fire) {
        s2_validReg := s1_validReg
        s2_pcReg := s1_pcReg
        if (isGShare) {
            s2_ghrSnapshotReg.get := ghrReg.get
            s2_predTakenReg.get := s1_predTaken
            s2_predPcReg.get := s1_predPc
            s2_predTypeReg.get := s1_predType
            s2_phtIdxReg.get := s1_phtIdx
        }
        //printf(p"S1: Send cache request for PC=0x${Hexadecimal(s1_pcReg)}\n")
    }.elsewhen(s2_respValid) {
        s2_validReg := false.B
        if (isGShare) {
            s2_predTakenReg.get := false.B
            s2_predPcReg.get := 0.U
            s2_predTypeReg.get := FrontendPredType.NONE
            s2_phtIdxReg.get := 0.U
        }
    }

    // ===== S2: Cache Response =====
    icache.io.drsp.ready := s2_validReg

    // ===== S3: Cache Response Registers =====
    // S3 不接受背压：当 S2 的返回有效时就装载；否则本拍拉低 valid。
    // 如果下一拍又有新的返回，S3 会直接被新的返回覆盖。
    s3_validReg := false.B
    when(!reset.asBool && !redirectValid && s2_respValid) {
        s3_validReg := true.B
        s3_pcReg := s2_pcReg
        s3_instReg := icache.io.drsp.bits.data
        if (isGShare) {
            s3_ghrSnapshotReg.get := s2_ghrSnapshotReg.get
            s3_predTakenReg.get := s2_predTakenReg.get
            s3_predPcReg.get := s2_predPcReg.get
            s3_predTypeReg.get := s2_predTypeReg.get
            s3_phtIdxReg.get := s2_phtIdxReg.get
        }
    }.elsewhen(reset.asBool || redirectValid) {
        if (isGShare) {
            s3_ghrSnapshotReg.get := 0.U
            s3_predTakenReg.get := false.B
            s3_predPcReg.get := 0.U
            s3_predTypeReg.get := FrontendPredType.NONE
            s3_phtIdxReg.get := 0.U
        }
    }

    // ===== S3: Fetch Buffer Output =====
    io.fetchBuffer.valid := s3_validReg
    io.fetchBuffer.bits.pc := s3_pcReg
    io.fetchBuffer.bits.inst := s3_instReg

    io.fetchBuffer.bits.pred.predType := s3_finalPredType
    io.fetchBuffer.bits.pred.predTaken := s3_finalPredTaken
    io.fetchBuffer.bits.pred.predPc := s3_finalPredPc
    io.fetchBuffer.bits.pred.phtIdx := 0.U

    // ===== GShare S3 Correction =====
    if(isGShare){
        miniDecode.get.io.pc := s3_pcReg
        miniDecode.get.io.inst := s3_instReg

        s3_finalPredType := s3_predTypeReg.get
        s3_finalPredTaken := s3_predTakenReg.get
        s3_finalPredPc := s3_predPcReg.get

        when(s3_validReg) {
            switch(miniDecode.get.io.predType) {
                is(FrontendPredType.JAL) {
                    s3_finalPredType := FrontendPredType.JAL
                    s3_finalPredTaken := true.B
                    s3_finalPredPc := miniDecode.get.io.predPc
                }
                is(FrontendPredType.BR) {
                    s3_finalPredType := FrontendPredType.BR
                    when(s3_predTakenReg.get) {
                        s3_finalPredPc := miniDecode.get.io.predPc
                    }
                }
                is(FrontendPredType.JALR) {
                    s3_finalPredType := FrontendPredType.JALR
                }
            }
        }

        s3_fastRedirectValid := s3_validReg && (
            (miniDecode.get.io.predType === FrontendPredType.JAL &&
                (!s3_predTakenReg.get || s3_predTypeReg.get =/= FrontendPredType.JAL ||
                    s3_predPcReg.get =/= miniDecode.get.io.predPc)) ||
            (miniDecode.get.io.predType === FrontendPredType.BR &&
                s3_predTakenReg.get &&
                s3_predPcReg.get =/= miniDecode.get.io.predPc)
        )
        s3_fastRedirectTarget := s3_finalPredPc
        io.fetchBuffer.bits.pred.phtIdx := s3_phtIdxReg.get
    }

    // ===== Cache Miss Interface =====
    icache.io.next_level_req <> io.nextLevelReq
    icache.io.next_level_rsp <> io.nextLevelRsp

    io.debug.foreach { debug =>
        debug.s1_pcReg := s1_pcReg
        debug.s1_valid := s1_validReg
        debug.dreq_valid := icache.io.dreq.valid
        debug.dreq_ready := icache.io.dreq.ready
        debug.dreq_fire := s1_fire
        debug.s2_pcReg := s2_pcReg
        debug.s2_valid := s2_validReg
        debug.s2_respValid := s2_respValid
        debug.cache_drsp_valid := icache.io.drsp.valid
        debug.cache_drsp_vaddr := icache.io.drsp.bits.vaddr
        debug.s3_pcReg := s3_pcReg
        debug.s3_valid := s3_validReg
        debug.cache_s0_ready := icache.io.debug.get.s0_ready
        debug.cache_s1_valid := icache.io.debug.get.s1_valid
        debug.cache_s1_hit := icache.io.debug.get.s1_hit
        debug.cache_s2_valid := icache.io.debug.get.s2_valid
        debug.cache_s2_done := icache.io.debug.get.s2_done
    }
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

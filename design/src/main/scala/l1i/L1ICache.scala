package flow.l1i

import chisel3._
import chisel3.util._
import flow.interface._
import _root_.circt.stage.ChiselStage
import flow.mem.flowSRAM
import flow.mmu.sv39.TreePlru
import flow.platform.{BreezeMcuPlatform, PMAAccessType, PMAChecker}
import svsim.CommonCompilationSettings.Timescale.Unit.s

class BreezeCacheDebugIO(cacheConfig: L1IParams) extends Bundle {
    val s0_valid = Output(Bool())
    val s0_vaddr = Output(UInt(cacheConfig.VLEN.W))
    val s0_ready = Output(Bool())
    val s1_valid = Output(Bool())
    val s1_vaddr = Output(UInt(cacheConfig.VLEN.W))
    val s1_meta = Output(UInt(cacheConfig.META_WIDTH.W))
    val s1_tag_hit = Output(UInt(cacheConfig.ICACHE_WAY_NUM.W))
    val s1_hit = Output(Bool())
    val s2_valid = Output(Bool())
    val s2_vaddr = Output(UInt(cacheConfig.VLEN.W))
    val s2_req_pulse_done = Output(Bool())
    val wait_rsp = Output(Bool())
    val s2_refill_done = Output(Bool())
    val s2_done = Output(Bool())
    val data_array_we = Output(UInt(cacheConfig.ICACHE_WAY_NUM.W))
    val tag_array_we = Output(UInt(cacheConfig.ICACHE_WAY_NUM.W))
}

/**
  * 当前的cache每次会向下一级的存储请求一个cache line的数据
  * 组数、路数和行宽由 L1IParams 推导，替换统一使用 TreePlru
  *
  * @param cacheConfig
  * @param enabledebug
  */
class L1ICache(val cacheConfig: L1IParams, val enabledebug: Boolean = false,val inspectsram:Boolean=false,
                  val parallelLookup: Boolean = false) extends Module {
    val io = IO(new Bundle{
        val dreq = Flipped(Decoupled(new BreezeCacheReqIO(cacheConfig.VLEN)))
        val arrayReq = if (parallelLookup) Some(Flipped(Decoupled(UInt(cacheConfig.VLEN.W)))) else None
        val drsp = Decoupled(new BreezeCacheRespIO(cacheConfig.VLEN,cacheConfig.FETCH_WIDTH))
        val flush = Input(Bool())
        val next_level_req = new L1CacheMissReqIO(cacheConfig.PLEN)
        val next_level_rsp = new L1CacheMissRespIO(cacheConfig.ICACHE_LINE_WIDTH)
        val hpm = Output(new BreezeHpmEvents)
        val debug = if(enabledebug) Some(new BreezeCacheDebugIO(cacheConfig)) else None
    })
    io.hpm := 0.U.asTypeOf(new BreezeHpmEvents)

    require(!parallelLookup || cacheConfig.ICACHE_SET_NUM * cacheConfig.ICACHE_LINE_BYTES <= 4096,
        "VIPT index must fit within the smallest Sv39 page")
    BreezeMcuPlatform.PMARegions.filter(_.supportsExecute).foreach { region =>
        require(region.cacheable, s"Executable PMA region ${region.name} must be cacheable")
        require(
            region.origin % cacheConfig.ICACHE_LINE_BYTES == 0 &&
            region.size % cacheConfig.ICACHE_LINE_BYTES == 0,
            s"Executable PMA region ${region.name} must be aligned to the ICache line size"
        )
    }
    val rawRsp = Wire(Decoupled(new BreezeCacheRespIO(cacheConfig.VLEN, cacheConfig.FETCH_WIDTH)))
    val returns = withReset(reset.asBool || io.flush) {
        Module(new Queue(new BreezeCacheRespIO(cacheConfig.VLEN, cacheConfig.FETCH_WIDTH),
            2, pipe = false, flow = false))
    }
    val outstanding = RegInit(0.U(2.W))
    returns.io.enq.valid := rawRsp.valid
    returns.io.enq.bits := rawRsp.bits
    rawRsp.ready := returns.io.enq.ready
    io.drsp.valid := returns.io.deq.valid && !io.flush
    io.drsp.bits := returns.io.deq.bits
    returns.io.deq.ready := io.drsp.ready && !io.flush
    // At most two accepted, undelivered requests. These include the S1/miss
    // owner, so a pulse response always has space even under output stalls.
    when(io.flush) { outstanding := 0.U }
      .elsewhen(io.dreq.fire =/= io.drsp.fire) {
        outstanding := Mux(io.dreq.fire, outstanding + 1.U, outstanding - 1.U)
      }
    val responseCredit = outstanding < 2.U || io.drsp.fire
    when(!reset.asBool && !io.flush) {
        assert(!rawRsp.valid || rawRsp.ready, "[SOC3e] I-cache return has no reserved capacity")
        assert(outstanding <= 2.U, "[SOC3e] I-cache response credits overflow")
        assert(!io.drsp.fire || outstanding =/= 0.U, "[SOC3e] I-cache returned an unowned request")
    }

    //initial IO
    //dreq
    io.dreq.ready := false.B
    //drsp
    rawRsp.valid := false.B
    rawRsp.bits.vaddr := 0xdeadbeefL.U
    rawRsp.bits.data := 0xdeadbeefL.U
    rawRsp.bits.accessFault := false.B
    rawRsp.bits.pageFault := false.B
    //next level req
    io.next_level_req.req := false.B
    io.next_level_req.paddr := 0xdeadbeefL.U

    val s0_valid = io.dreq.fire
    val s0_vaddr = io.dreq.bits.vaddr
    val s0_paddr = io.dreq.bits.paddr

    // PMA classification runs in parallel with the normal s0 cache-array
    // request. This MCU only caches instruction fetches from executable,
    // cacheable physical regions.
    val fetchPma = Module(new PMAChecker)
    fetchPma.io.query.addr := io.dreq.bits.paddr
    fetchPma.io.query.sizeLog2 := 2.U // RV64I instruction fetch: 4 bytes
    fetchPma.io.query.accessType := PMAAccessType.Fetch

    val s1_valid = RegInit(false.B)
    val s1_vaddr = RegInit(0.U(cacheConfig.VLEN.W))
    val s1_paddr = RegInit(0.U(cacheConfig.PLEN.W))
    val s1_pma_allowed = RegInit(false.B)
    val s1_pma_cacheable = RegInit(false.B)
    when(io.flush) {
        s1_valid := false.B
        s1_pma_allowed := false.B
        s1_pma_cacheable := false.B
    }.otherwise {
        s1_valid := s0_valid
        s1_vaddr := s0_vaddr
        s1_paddr := s0_paddr
        s1_pma_allowed := s0_valid && fetchPma.io.result.allowed
        s1_pma_cacheable := s0_valid && fetchPma.io.result.cacheable
    }
    val s1_tag_hit = Wire(Vec(cacheConfig.ICACHE_WAY_NUM, Bool()))
    val s1_hit = s1_tag_hit.reduce(_ || _)
    val s1_dout = Wire(UInt(cacheConfig.FETCH_WIDTH.W))

    
    
    // sram
    val tag_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_TAG_WIDTH,"tag",inspectsram)))
    val data_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_LINE_WIDTH,"data",inspectsram)))
    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
        tag_array(i).io.addr := 0.U
        tag_array(i).io.data_in := 0.U
        tag_array(i).io.we := false.B
        tag_array(i).io.re := false.B
        data_array(i).io.addr := 0.U
        data_array(i).io.data_in := 0.U
        data_array(i).io.we := false.B
        data_array(i).io.re := false.B
        data_array(i).io.id := i.U
        tag_array(i).io.id := i.U
    }
    val metaReg = RegInit(VecInit(Seq.fill(cacheConfig.ICACHE_SET_NUM)(0.U(cacheConfig.META_WIDTH.W)))) // PLRU, .....valid[1],valid[0]
    //s1 read tag and data
    val snapshotTags = Reg(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.ICACHE_TAG_WIDTH.W)))
    val snapshotData = Reg(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.ICACHE_LINE_WIDTH.W)))
    val snapshotIndex = Reg(UInt(cacheConfig.ICACHE_INDEX_WIDTH.W))
    val snapshotValid = RegInit(false.B)
    val snapshotUsable = parallelLookup.B && snapshotValid &&
        snapshotIndex === index_pos(s0_vaddr, cacheConfig)
    val s1_useSnapshot = RegNext(s0_valid && snapshotUsable, false.B)
    val earlyRead = WireDefault(false.B)
    val earlyIndex = WireDefault(0.U(cacheConfig.ICACHE_INDEX_WIDTH.W))
    val can_read_array = (s0_valid && !snapshotUsable && fetchPma.io.result.allowed && fetchPma.io.result.cacheable) || earlyRead
    val cacheline_index = Mux(earlyRead, earlyIndex, index_pos(s0_vaddr, cacheConfig))
    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
        tag_array(i).io.addr := cacheline_index
        tag_array(i).io.re := can_read_array
        data_array(i).io.addr := cacheline_index
        data_array(i).io.re := can_read_array
    }
    val rawTags = VecInit(tag_array.map(_.io.data_out))
    val rawData = VecInit(data_array.map(_.io.data_out))
    val tag_array_rdata = Mux(s1_useSnapshot, snapshotTags, rawTags)
    val data_array_rdata = Mux(s1_useSnapshot, snapshotData, rawData)
    val captureEarly = RegNext(earlyRead, false.B)
    when(earlyRead) { snapshotIndex := earlyIndex }
    when(captureEarly) {
        snapshotTags := rawTags
        snapshotData := rawData
        snapshotValid := true.B
    }
    val s1_word_offset = s1_vaddr(cacheConfig.ICACHE_LINE_OFFSET_WIDTH + cacheConfig.ICACHE_BYTES_OFFSET_WIDTH - 1, cacheConfig.ICACHE_BYTES_OFFSET_WIDTH)
    val s1_way_dout = Wire(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.FETCH_WIDTH.W)))
    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
        s1_way_dout(i) := data_array_rdata(i) >> (s1_word_offset * cacheConfig.FETCH_WIDTH.U)
    }
    //s1 compare tag
    val desired_tag = s1_paddr(cacheConfig.PLEN - 1, cacheConfig.ICACHE_INDEX_WIDTH + cacheConfig.ICACHE_LINE_OFFSET_WIDTH + cacheConfig.ICACHE_BYTES_OFFSET_WIDTH)
    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
        val s1_index = index_pos(s1_vaddr, cacheConfig)
        val s1_vld = metaReg(s1_index)(i) // valid bit
        val tag_match = tag_array_rdata(i) === desired_tag
        s1_tag_hit(i) := s1_vld && tag_match
    }
    // Blocking refill preserves one copy of each tag in a set.
    when(s1_valid) {
        assert(PopCount(s1_tag_hit) <= 1.U, "ICache: duplicate matching ways")
    }
    s1_dout := Mux1H(s1_tag_hit, s1_way_dout)
    val s1_pma_fault = s1_valid && (!s1_pma_allowed || !s1_pma_cacheable)
    val s1_miss = s1_valid && !s1_pma_fault && !s1_hit
    // 当s2有miss需要处理的时候，会阻塞s0,s1的正常运行，直到s2处理完成
    // s2正在处理的时候，不会有其他的访问进入流水线
    val s2_done = WireDefault(false.B) //标志s2的miss处理完成
    //val s2_valid = RegNext(s1_valid && !s1_hit, false.B)
    val s2_valid = RegInit(false.B)
    if (parallelLookup) {
        io.arrayReq.get.ready := !io.flush && !s1_valid && !s2_valid && !io.dreq.valid
        earlyRead := io.arrayReq.get.fire
        earlyIndex := index_pos(io.arrayReq.get.bits, cacheConfig)
    }
    // No externally visible activity is caused by the early read. A fence,
    // refill or intervening real request makes its saved array data unusable.
    when(earlyRead || io.flush || s0_valid || s1_valid || s2_valid) { snapshotValid := false.B }
    val s2_vaddr = RegInit(0.U(cacheConfig.VLEN.W))
    val s2_paddr = RegInit(0.U(cacheConfig.PLEN.W))
    val s2_word_offset = RegInit(0.U(s1_word_offset.getWidth.W))
    val s2_req_pulse_done = RegInit(false.B) //标志当前这笔 miss 是否已经发出过请求脉冲
    val wait_rsp = RegInit(false.B) //cache正在等待下一级的响应
    val s2_flush_seen = RegInit(false.B)
    when(io.flush){
        when(s2_done){
            s2_valid := false.B
            s2_flush_seen := false.B
        }.elsewhen(s2_valid && (s2_req_pulse_done || wait_rsp)){
            s2_flush_seen := true.B
        }.otherwise{
            s2_valid := false.B
            s2_flush_seen := false.B
        }
    }.elsewhen(s1_valid){
        when(s1_pma_fault || s1_hit){
            s2_valid := false.B
        }.otherwise{
            s2_valid := true.B
            s2_vaddr := s1_vaddr
            s2_paddr := s1_paddr
            s2_word_offset := s1_word_offset
            s2_flush_seen := false.B
        }
    }.elsewhen(s2_done){
        s2_valid := false.B
        s2_flush_seen := false.B
    }
    //val s2_vaddr = RegNext(s1_vaddr)
    val line_addr = WireDefault(0.U(cacheConfig.PLEN.W))
    line_addr := s2_paddr & ~((cacheConfig.ICACHE_LINE_BYTES - 1).U(cacheConfig.PLEN.W)) // cache line对齐
    //在s2选择要替换到那一个way
    val s2_index = index_pos(s2_vaddr, cacheConfig)
    //首先检查有没有无效的way，如果有的话直接替换第一个无效的way
    val s2_entry_meta = metaReg(s2_index)
    val ways = cacheConfig.ICACHE_WAY_NUM
    val validWays = s2_entry_meta(ways-1, 0)
    val plruState = if (ways == 1) 0.U(0.W) else s2_entry_meta(cacheConfig.META_WIDTH-1, ways)
    val victim = Mux(!validWays.andR, PriorityEncoder(~validWays), TreePlru.victim(plruState, ways))
    val wb_en_OH = UIntToOH(victim, ways)
    val new_valid_vec = validWays | wb_en_OH
    val new_plru_vec = TreePlru.touch(plruState, victim, ways)
    // 暂存控制信号
    val s2_new_valid_vec = Reg(UInt(cacheConfig.ICACHE_WAY_NUM.W))
    val s2_new_plru_vec = Reg(UInt(cacheConfig.PLRU_WIDTH.W))
    val s2_wt_en_OH = Reg(UInt(cacheConfig.ICACHE_WAY_NUM.W))
    val s2_refill_tag = Reg(UInt(cacheConfig.ICACHE_TAG_WIDTH.W))
    when(s2_valid){
        s2_new_valid_vec := new_valid_vec
        s2_new_plru_vec := new_plru_vec
        s2_wt_en_OH := wb_en_OH
        s2_refill_tag := s2_paddr(cacheConfig.PLEN - 1, cacheConfig.ICACHE_INDEX_WIDTH + cacheConfig.ICACHE_LINE_OFFSET_WIDTH + cacheConfig.ICACHE_BYTES_OFFSET_WIDTH)
    }
    //当s1 miss时，发出miss请求
    //当请求进入s2以后，先发送一个脉冲式的请求，然后就是等待cache line返回
    //之后当cache line长度的数据返回以后，同周期进行数据的写回和meta的更新
    val s2_dout = RegInit(0.U(cacheConfig.FETCH_WIDTH.W))
    val s2_refill_error = RegInit(false.B)
    //向下一级的存储发送一个脉冲式的请求
    val issue_s2_req_pulse = s2_valid && !s2_req_pulse_done && !io.flush && !s2_flush_seen
    io.hpm.icacheAccess := s0_valid
    io.hpm.icacheMiss := issue_s2_req_pulse

    when(s2_done) { // 当前 miss 结束，给下一笔 miss 重新打开请求脉冲
        s2_req_pulse_done := false.B
    }.elsewhen(issue_s2_req_pulse) { // 新 miss 进入 s2 的首拍发出请求脉冲
        s2_req_pulse_done := true.B
        s2_refill_error := false.B
    }

    when(io.next_level_rsp.vld && wait_rsp) {
        s2_refill_error := io.next_level_rsp.error
    }

    when(s2_done) {
        wait_rsp := false.B
    }.elsewhen(issue_s2_req_pulse) {
        wait_rsp := true.B
    }.elsewhen(io.next_level_rsp.vld && wait_rsp) {
        wait_rsp := false.B
    }

    // 一个周期的对外的请求脉冲
    io.next_level_req.req := issue_s2_req_pulse
    io.next_level_req.paddr := line_addr // translated physical line address
    //等待下一级的存储返回数据
    val write_back_en = WireDefault(false.B)
    val refill_response = io.next_level_rsp.vld && wait_rsp
    write_back_en := refill_response && !io.next_level_rsp.error && !io.flush && !s2_flush_seen
    //数据写回和meta更新
    //覆盖对sram的写
    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
        // 先写回数据
        // Refill belongs to the outstanding s2 miss.  io.dreq.bits may already
        // carry a redirect target even though that request is backpressured, so
        // using the normal s0 read address here can write a returned line into
        // the wrong set.
        when(write_back_en) {
            data_array(i).io.addr := s2_index
            tag_array(i).io.addr := s2_index
        }
        data_array(i).io.data_in := io.next_level_rsp.data //不分bank了，直接全线写回
        data_array(i).io.we := write_back_en && s2_wt_en_OH(i)
        // tag 的更新
        tag_array(i).io.data_in := s2_refill_tag
        tag_array(i).io.we := write_back_en && s2_wt_en_OH(i)
    }
    val s2_refill_done = RegNext(refill_response, false.B)
    when(refill_response && !io.next_level_rsp.error){
        s2_dout := io.next_level_rsp.data >> (s2_word_offset * cacheConfig.FETCH_WIDTH.U)
    }
    //在更新完数据和tag以后，更新meta
    when(io.flush) {
        for(i <- 0 until cacheConfig.ICACHE_SET_NUM){
            metaReg(i) := 0.U
        }
    }.elsewhen(s2_req_pulse_done && write_back_en){
        metaReg(s2_index) := s2_new_plru_vec ## s2_new_valid_vec // PLRU位在高位，valid位在低位，默认4 ways
        //printf(p"*********************s2 refill done, update meta: index=0x${Hexadecimal(s2_index)}, new_meta=0b${Binary(s2_new_plru_vec)}_${Binary(s2_new_valid_vec)}\n")
    }
    s2_done := s2_refill_done

    //当后面有进入miss处理的时候，不再允许接收新的请求，直到miss处理完成
    val stop_new_req = s1_miss || s2_valid && !s2_done
    io.dreq.ready := !io.flush && responseCredit && ~(stop_new_req) //当s1 valid且miss时，阻止新的请求进入，然后一直到s2处理完成才允许新的请求进入
    
    def index_pos(vaddr:UInt,cfg:L1IParams): UInt = {
        if (cfg.ICACHE_SET_NUM == 1) 0.U(1.W)
        else vaddr(cfg.ICACHE_INDEX_WIDTH + cfg.ICACHE_LINE_OFFSET_WIDTH + cfg.ICACHE_BYTES_OFFSET_WIDTH - 1, cfg.ICACHE_LINE_OFFSET_WIDTH + cfg.ICACHE_BYTES_OFFSET_WIDTH)
    }
    
    // handshake and response: prefer returning the refill result when s2 completes
    when(!io.flush && !s2_flush_seen && s2_valid && s2_done){
        rawRsp.valid := true.B
        rawRsp.bits.vaddr := s2_vaddr
        rawRsp.bits.data := Mux(s2_refill_error, 0.U, s2_dout)
        rawRsp.bits.accessFault := s2_refill_error
        rawRsp.bits.pageFault := false.B
    }.elsewhen(!io.flush && s1_pma_fault){
        rawRsp.valid := true.B
        rawRsp.bits.vaddr := s1_vaddr
        rawRsp.bits.data := 0.U
        rawRsp.bits.accessFault := true.B
        rawRsp.bits.pageFault := false.B
    }.elsewhen(!io.flush && s1_valid && s1_hit){
        rawRsp.valid := true.B
        rawRsp.bits.vaddr := s1_vaddr
        rawRsp.bits.data := s1_dout
        rawRsp.bits.accessFault := false.B
        rawRsp.bits.pageFault := false.B
    }.otherwise{
        rawRsp.valid := false.B
        rawRsp.bits.vaddr := 0.U
        rawRsp.bits.data := 0.U
        rawRsp.bits.accessFault := false.B
        rawRsp.bits.pageFault := false.B
    }

    if(enabledebug){
        io.debug.get.s0_valid := s0_valid
        io.debug.get.s0_vaddr := s0_vaddr
        io.debug.get.s0_ready := io.dreq.ready
        io.debug.get.s1_valid := s1_valid
        io.debug.get.s1_vaddr := s1_vaddr
        io.debug.get.s1_meta := metaReg(index_pos(s1_vaddr, cacheConfig))
        io.debug.get.s1_tag_hit := s1_tag_hit.asUInt
        io.debug.get.s1_hit := s1_hit
        io.debug.get.s2_valid := s2_valid
        io.debug.get.s2_vaddr := s2_vaddr
        io.debug.get.s2_req_pulse_done := s2_req_pulse_done
        io.debug.get.wait_rsp := wait_rsp
        io.debug.get.s2_refill_done := s2_refill_done
        io.debug.get.s2_done := s2_done
        io.debug.get.data_array_we := VecInit(data_array.map(_.io.we)).asUInt
        io.debug.get.tag_array_we := VecInit(tag_array.map(_.io.we)).asUInt
    }
}

object GenerateL1ICache extends App {
    ChiselStage.emitSystemVerilogFile(
        new L1ICache(L1IParams()),
        Array("--target-dir", "build"),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "-default-layer-specialization=enable"
        )
    )
} 

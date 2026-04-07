package flow.cache

import chisel3._
import chisel3.util._
import flow.interface._
import _root_.circt.stage.ChiselStage
import flow.config.DefaultICacheConfig
import flow.mem.flowSRAM

class BreezeCacheDebugIO(vlen: Int) extends Bundle {
    val s0_valid = Output(Bool())
    val s0_vaddr = Output(UInt(vlen.W))
    val s1_valid = Output(Bool())
    val s1_vaddr = Output(UInt(vlen.W))
    val s1_tag_hit = Output(UInt(DefaultICacheConfig().ICACHE_WAY_NUM.W))
    val s1_hit = Output(Bool())
}

/**
  * 当前的cache每次会向下一级的存储请求一个cache line的数据
  *
  * @param cacheConfig
  * @param enabledebug
  */
class BreezeCache(val cacheConfig: DefaultICacheConfig, val enabledebug: Boolean = false) extends Module {
    val io = IO(new Bundle{
        val dreq = Flipped(Decoupled(new BreezeCacheReqIO(cacheConfig.VLEN)))
        val drsp = Decoupled(new BreezeCacheRespIO(cacheConfig.VLEN,cacheConfig.FETCH_WIDTH))
        val next_level_req = new L1CacheMissReqIO(cacheConfig.PLEN)
        val next_level_rsp = new L1CacheMissRespIO(cacheConfig.ICACHE_LINE_WIDTH)
        val debug = if(enabledebug) Some(new BreezeCacheDebugIO(cacheConfig.VLEN)) else None
    })
    //initial IO
    //dreq
    io.dreq.ready := false.B
    //drsp
    io.drsp.valid := false.B
    io.drsp.bits.vaddr := 0xdeadbeefL.U
    io.drsp.bits.data := 0xdeadbeefL.U
    //next level req
    io.next_level_req.req := false.B
    io.next_level_req.paddr := 0xdeadbeefL.U

    val s0_valid = io.dreq.fire
    val s0_vaddr = io.dreq.bits.vaddr

    val s1_valid = RegNext(s0_valid)
    val s1_vaddr = RegNext(s0_vaddr,s0_valid)
    val s1_tag_hit = Wire(Vec(cacheConfig.ICACHE_WAY_NUM, Bool()))
    val s1_hit = s1_tag_hit.reduce(_ || _)
    val s1_dout = Wire(UInt(cacheConfig.FETCH_WIDTH.W))
    //当后面有进入miss处理的时候，不再允许接收新的请求，直到miss处理完成
    io.dreq.ready := !s1_valid || s1_hit
    // sram
    val tag_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_TAG_WIDTH)))
    val data_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_LINE_WIDTH)))
    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
        tag_array(i).io.addr := 0.U
        tag_array(i).io.data_in := 0.U
        tag_array(i).io.we := false.B
        tag_array(i).io.re := false.B
        data_array(i).io.addr := 0.U
        data_array(i).io.data_in := 0.U
        data_array(i).io.we := false.B
        data_array(i).io.re := false.B
    }
    val metaReg = RegInit(VecInit(Seq.fill(cacheConfig.ICACHE_WAY_NUM)(0.U(cacheConfig.META_WIDTH.W)))) // valid + PLRU
    //s1 read tag and data
    val can_read_array = s0_valid
    val cacheline_index = s0_vaddr(cacheConfig.ICACHE_INDEX_WIDTH + cacheConfig.ICACHE_OFFSET_WIDTH - 1, cacheConfig.ICACHE_OFFSET_WIDTH)
    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
        tag_array(i).io.addr := cacheline_index
        tag_array(i).io.re := can_read_array
        data_array(i).io.addr := cacheline_index
        data_array(i).io.re := can_read_array
    }
    val tag_array_rdata = tag_array.map(_.io.data_out)
    val data_array_rdata = data_array.map(_.io.data_out)
    val s1_word_offset = s1_vaddr(cacheConfig.ICACHE_OFFSET_WIDTH - 1, log2Ceil(cacheConfig.FETCH_WIDTH / 8))
    val s1_way_dout = Wire(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.FETCH_WIDTH.W)))
    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
        s1_way_dout(i) := data_array_rdata(i) >> (s1_word_offset * cacheConfig.FETCH_WIDTH.U)
    }
    //s1 compare tag
    val desired_tag = s1_vaddr(cacheConfig.VLEN - 1, cacheConfig.ICACHE_INDEX_WIDTH + cacheConfig.ICACHE_OFFSET_WIDTH)
    for(i <- 0 until cacheConfig.ICACHE_WAY_NUM){
        val s1_vld = metaReg(i)(cacheConfig.META_WIDTH - 1)
        val tag_match = tag_array_rdata(i) === desired_tag
        s1_tag_hit(i) := s1_vld && tag_match
    }
    s1_dout := Mux1H(s1_tag_hit, s1_way_dout)
    // 当s2有miss需要处理的时候，会阻塞s0,s1的正常运行，直到s2处理完成
    val s2_busy = RegInit(false.B)
    val s2_valid = RegNext(s1_valid && !s1_hit & !s2_busy, false.B)
    val s2_vaddr = RegNext(s1_vaddr)
    val line_addr = WireDefault(0.U(cacheConfig.PLEN.W))
    line_addr := ???
    //当s1 miss时，发出miss请求
    //当请求进入s2以后，先发送一个脉冲式的请求，然后就是等待cache line返回
    //之后当cache line长度的数据返回以后，同周期进行数据的写回和meta的更新
    //
    val s2_dout = Wire(UInt(cacheConfig.FETCH_WIDTH.W))
    //向下一级的存储发送一个脉冲式的请求
    val s2_req_pulse = RegInit(false.B)
    when(s2_valid){
        s2_req_pulse := true.B
    }.otherwise{
        s2_req_pulse := false.B
    }
    // 一个周期的对外的请求脉冲
    io.next_level_req.req := s2_req_pulse
    io.next_level_req.paddr := s2_vaddr // 目前直接使用vaddr作为paddr，后续会加入地址转换模块
    //等待下一级的存储返回数据
    val write_back_en = WireDefault(false.B)
    val s2_rsp_data = Reg(UInt(cacheConfig.FETCH_WIDTH.W))
    val s2_rsp_vld = RegInit(false.B)
    when(io.next_level_rsp.vld){
        write_back_en := true.B
        s2_rsp_data := io.next_level_rsp.data
        s2_rsp_vld := true.B
    }.otherwise{
        write_back_en := false.B
        s2_rsp_vld := false.B
    }
    

    // 目前将使用状态机显式的管理miss处理的流程，后续可以考虑使用更优雅的方式
    io.next_level_req.req := s2_vaddr
    io.next_level_req.paddr := s2_vaddr // 目前直接使用vaddr作为paddr，后续会加入地址转换模块
    // hanshake and response,一个周期的脉冲，下游的责任去接收
    io.drsp.valid := s1_valid & s1_hit
    io.drsp.bits.data := s1_dout

    if(enabledebug){
        io.debug.get.s0_valid := s0_valid
        io.debug.get.s0_vaddr := s0_vaddr
        io.debug.get.s1_valid := s1_valid
        io.debug.get.s1_vaddr := s1_vaddr
        io.debug.get.s1_tag_hit := s1_tag_hit.asUInt
        io.debug.get.s1_hit := s1_hit
    }
}

object GenerateBreezeCacheVerilogFile extends App {
    ChiselStage.emitSystemVerilogFile(
        new BreezeCache(DefaultICacheConfig()),
        Array("--target-dir", "build"),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "-default-layer-specialization=enable"
        )
    )
} 

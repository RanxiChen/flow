package flow.cache

import chisel3._
import chisel3.util._
import flow.interface._
import _root_.circt.stage.ChiselStage
import flow.config.DefaultICacheConfig
import flow.mem.flowSRAM

class BreezeCache(val cacheConfig: DefaultICacheConfig) extends Module {
    val io = IO(new Bundle{
        val boot_addr = Input(UInt(cacheConfig.VLEN.W))
        val dreq = Flipped(Decoupled(new BreezeCacheReqIO(cacheConfig.VLEN)))
        val drsp = Decoupled(new BreezeCacheRespIO(cacheConfig.VLEN,cacheConfig.FETCH_WIDTH))
        val next_level_req = new L1CacheMissReqIO(cacheConfig.PLEN)
        val next_level_rsp = new L1CacheMissRespIO(cacheConfig.ICACHE_LINE_WIDTH)
    })
    //initial IO
    io.dreq.valid := false.B
    io.dreq.bits.vaddr := 0.U
    io.drsp.valid := false.B
    //采用rocket的隐式状态机的样子
    //第一步，先实现可读的cache
    //s0 get vaddr, s1 return hit/miss
    //当前默认可以一直收到请求，并且每次都命中
    val s0_valid = io.dreq.fire
    val s0_vaddr = io.dreq.bits.vaddr

    val s1_valid = RegNext(s0_valid)
    val s1_vaddr = RegNext(s0_vaddr,s0_valid)
    val s1_tag_hit = Wire(Vec(cacheConfig.ICACHE_WAY_NUM, Bool()))
    val s1_hit = s1_tag_hit.reduce(_ || _)
    val s1_dout = Wire(UInt(cacheConfig.FETCH_WIDTH.W))
    // sram
    val tag_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_TAG_WIDTH)))
    val data_array = Seq.fill(cacheConfig.ICACHE_WAY_NUM)(Module(new flowSRAM(cacheConfig.ICACHE_SET_NUM, cacheConfig.ICACHE_LINE_WIDTH)))
    val metaReg = Reg(Vec(cacheConfig.ICACHE_WAY_NUM, UInt(cacheConfig.META_WIDTH.W))) // valid + PLRU
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
    // hanshake and response,一个周期的脉冲，下游的责任去接收
    io.drsp.valid := s1_valid & s1_hit
    io.drsp.bits.data := s1_dout
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

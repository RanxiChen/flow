package flow.config

import chisel3.util.log2Ceil
/**
  * 存放组相连的ICache的配置参数,采用VIPT结构
  * 这个类对应的替换算法是LRU,目前不考虑其他替换算法
  *
  */
case class DefaultICacheConfig(
    VLEN: Int = 64, // 虚拟地址的位宽，默认是64位
    PLEN: Int = 64, // 物理地址的位宽，默认是64位
    ICACHE_LINE_WIDTH: Int = 256, // cache line的位宽，默认是32byte = 32 * 8bit = 256 bits
    ICACHE_SET_NUM: Int = 64, // cache set的数量，默认是64
    ICACHE_WAY_NUM: Int = 4, // cache的路数，默认是4
    FETCH_WIDTH: Int = 32 // 从cache line中每次取出的指令位宽，默认是32bit = 4byte    
){
    val ICACHE_OFFSET_WIDTH: Int = log2Ceil(ICACHE_LINE_WIDTH / 8) // cache line内的偏移位宽
    val ICACHE_INDEX_WIDTH: Int = log2Ceil(ICACHE_SET_NUM) // cache set的索引位宽
    val ICACHE_TAG_WIDTH: Int = VLEN - ICACHE_INDEX_WIDTH - ICACHE_OFFSET_WIDTH // cache tag的位宽
    val PLRU_WIDTH: Int = ICACHE_WAY_NUM - 1 // PLRU替换算法需要的位宽
    val META_WIDTH: Int = PLRU_WIDTH + 1 // 每个cache line需要存储的元信息位宽，包括valid位和PLRU位
}
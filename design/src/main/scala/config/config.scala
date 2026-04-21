package flow.config

import chisel3.util.log2Ceil
/**
  * 存放组相连的ICache的配置参数,采用VIPT结构
  * 这个类对应的替换算法是LRU,目前不考虑其他替换算法
  * 因为现在的伪LRU算法是硬编码成4路组相连的cache的，所以暂时不考虑其他路数的cache
  */
case class DefaultICacheConfig(
    VLEN: Int = 64, // 虚拟地址的位宽，默认是64位
    PLEN: Int = 64, // 物理地址的位宽，默认是64位
    ICACHE_LINE_BYTES:Int = 32, // cache line的字节数，默认是32B
    ICACHE_SET_NUM: Int = 64, // cache set的数量，默认是64
    ICACHE_WAY_NUM: Int = 4, // cache的路数，默认是4
    FETCH_WIDTH: Int = 32 // 从cache line中每次取出的指令位宽，默认是32bit = 4byte    
){
    val ICACHE_LINE_WIDTH: Int = ICACHE_LINE_BYTES * 8 // cache line的位宽，默认是32byte = 32 * 8bit = 256 bits
    val ICACHE_BYTES_OFFSET_WIDTH = log2Ceil(FETCH_WIDTH / 8) // 地址将会是按照fetch width对齐
    val ICACHE_LINE_OFFSET_WIDTH = log2Ceil(ICACHE_LINE_BYTES) - ICACHE_BYTES_OFFSET_WIDTH // cache line内的偏移位宽
    val ICACHE_INDEX_WIDTH: Int = log2Ceil(ICACHE_SET_NUM) // cache set的索引位宽
    val ICACHE_TAG_WIDTH: Int = VLEN - ICACHE_INDEX_WIDTH - ICACHE_LINE_OFFSET_WIDTH - ICACHE_BYTES_OFFSET_WIDTH // cache tag的位宽
    val PLRU_WIDTH: Int = ICACHE_WAY_NUM - 1 // PLRU替换算法需要的位宽
    val META_WIDTH: Int = PLRU_WIDTH + ICACHE_WAY_NUM // 每个cache line需要存储的元信息位宽，包括valid位和PLRU位,选择valid放到低位
    assert(ICACHE_WAY_NUM == 4, "当前只支持4路组相连的cache")
}

object FrontendBranchPredictorKind extends Enumeration {
    type FrontendBranchPredictorKind = Value
    val None, GShare = Value
}

import FrontendBranchPredictorKind._

sealed trait FrontendBranchPredictorConfig {
    def kind: FrontendBranchPredictorKind
}

case object NoBranchPredictorConfig extends FrontendBranchPredictorConfig {
    override val kind: FrontendBranchPredictorKind = FrontendBranchPredictorKind.None
}

case object GShareBranchPredictorConfig extends FrontendBranchPredictorConfig {
    override val kind: FrontendBranchPredictorKind = FrontendBranchPredictorKind.GShare
}

case class BreezeFrontendConfig(
    VLEN: Int = 64,
    branchPredCfg: FrontendBranchPredictorConfig = NoBranchPredictorConfig
) {
    val cacheCfg: DefaultICacheConfig = DefaultICacheConfig(
        VLEN = VLEN,
        PLEN = VLEN
    )
}

case class BackendConfig(
    val VLEN: Int = 64,
    val PLEN: Int = 64
){}

case class BreezeCoreConfig(
    val VLEN: Int = 64,
    val PLEN: Int = 64,
    val useFASE: Boolean = false
){
    val frontendCfg: BreezeFrontendConfig = BreezeFrontendConfig(VLEN)
    val backendCfg: BackendConfig = BackendConfig(VLEN, PLEN)
}

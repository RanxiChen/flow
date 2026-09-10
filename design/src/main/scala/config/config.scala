package flow.config

import chisel3.util.log2Ceil

/** Preserve the 16-entry CSR layout; upper entries are hardwired OFF/zero. */
object BreezePmpConfig {
    val CsrEntries = 16
    val ActiveEntries = 8
}

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
    def ghrLength: Int = 0
    def btbEntryNum: Int = 0
}

case object NoBranchPredictorConfig extends FrontendBranchPredictorConfig {
    override val kind: FrontendBranchPredictorKind = FrontendBranchPredictorKind.None
}

case class GShareBranchPredictorConfig(
    override val ghrLength: Int = 8,
    override val btbEntryNum: Int = 16
) extends FrontendBranchPredictorConfig {
    require(ghrLength > 0, "GShare ghrLength must be greater than 0")
    require(btbEntryNum > 0, "GShare btbEntryNum must be greater than 0")

    override val kind: FrontendBranchPredictorKind = FrontendBranchPredictorKind.GShare
}

case class BreezeFrontendConfig(
    VLEN: Int = 64,
    branchPredCfg: FrontendBranchPredictorConfig = GShareBranchPredictorConfig(),
    enableCompressed: Boolean = false,
    enableMmu: Boolean = false
) {
    val cacheCfg: DefaultICacheConfig = DefaultICacheConfig(
        VLEN = VLEN,
        PLEN = VLEN
    )
}

case class BackendConfig(
    val VLEN: Int = 64,
    val PLEN: Int = 64,
    val branchPredKind: FrontendBranchPredictorKind = FrontendBranchPredictorKind.GShare,
    val ghrLength: Int = 8,
    val enableTandem: Boolean = false,
    val privilegeProfile: PrivilegeProfile = PrivilegeProfile.Mcu,
    val enableCompressed: Boolean = false,
    val enableMmu: Boolean = false
){}

/** Compile-time privileged-architecture profile.  `mcu` preserves the
  * machine-only core used by the existing firmware.  `linux` enables the
  * M/S/U trap and CSR architecture while address translation remains Bare;
  * Sv39 is a later, orthogonal extension.
  */
sealed abstract class PrivilegeProfile(
    val name: String,
    val enableSupervisorUser: Boolean
)
object PrivilegeProfile {
    case object Mcu extends PrivilegeProfile("mcu", enableSupervisorUser = false)
    case object Linux extends PrivilegeProfile("linux", enableSupervisorUser = true)

    val all: Seq[PrivilegeProfile] = Seq(Mcu, Linux)

    def fromName(name: String): PrivilegeProfile =
        all.find(_.name == name).getOrElse(
            throw new IllegalArgumentException(
                s"unsupported privilege profile: $name (expected mcu or linux)"))
}

/** Parameters that materially change DCache hardware cost or performance.
  *
  * Frozen L1D geometry: 8192 B, 32 B lines, 4 ways -> 64 sets. The tag width
  * is derived from the 32-bit physical address slicing (tag=addr[31:11]).
  */
case class DefaultDCacheConfig(
    VLEN: Int = 64,
    PLEN: Int = 64,
    capacityBytes: Int = 8192,
    lineBytes: Int = 32,
    ways: Int = 4
) {
    require(capacityBytes > 0 && (capacityBytes & (capacityBytes - 1)) == 0,
        "DCache capacity must be a positive power of two")
    require(lineBytes >= 8 && (lineBytes & (lineBytes - 1)) == 0,
        "DCache line size must be a power of two and at least 8 bytes")
    require(lineBytes % 8 == 0, "DCache line must contain complete 64-bit words")
    require(ways > 0 && (ways & (ways - 1)) == 0,
        "DCache associativity must be a positive power of two")
    require(capacityBytes % (lineBytes * ways) == 0,
        "DCache capacity must be divisible by lineBytes * ways")

    val sets: Int = capacityBytes / (lineBytes * ways)
    require(sets > 0 && (sets & (sets - 1)) == 0,
        "DCache set count must be a positive power of two")

    val lineWidth: Int = lineBytes * 8
    val lineOffsetWidth: Int = log2Ceil(lineBytes)
    val setIndexWidth: Int = log2Ceil(sets)
    val wayIndexWidth: Int = log2Ceil(ways)
    val tagWidth: Int = 32 - lineOffsetWidth - setIndexWidth
    val plruWidth: Int = ways - 1
    val metaWidth: Int = 2 * ways + plruWidth
}

case class BreezeCoreConfig(
    val VLEN: Int = 64,
    val PLEN: Int = 64,
    val useFASE: Boolean = false,
    val enableTandem: Boolean = false,
    val useGShare: Boolean = true,
    val gshareGhrLength: Int = 8,
    val gshareBtbEntryNum: Int = 16,
    val dcacheCapacityBytes: Int = 8192,
    val dcacheLineBytes: Int = 32,
    val dcacheWays: Int = 4,
    val privilegeProfile: PrivilegeProfile = PrivilegeProfile.Mcu,
    val enableCompressed: Boolean = false,
    val enableMmu: Boolean = false
){
    private val branchPredCfg: FrontendBranchPredictorConfig =
        if (useGShare) {
            GShareBranchPredictorConfig(
                ghrLength = gshareGhrLength,
                btbEntryNum = gshareBtbEntryNum
            )
        } else {
            NoBranchPredictorConfig
        }

    val frontendCfg: BreezeFrontendConfig = BreezeFrontendConfig(
        VLEN = VLEN,
        branchPredCfg = branchPredCfg,
        enableCompressed = enableCompressed,
        enableMmu = enableMmu
    )
    val backendCfg: BackendConfig = BackendConfig(
        VLEN = VLEN,
        PLEN = PLEN,
        branchPredKind = frontendCfg.branchPredCfg.kind,
        ghrLength = frontendCfg.branchPredCfg.ghrLength,
        enableTandem = enableTandem,
        privilegeProfile = privilegeProfile,
        enableCompressed = enableCompressed,
        enableMmu = enableMmu
    )
    val dcacheCfg: DefaultDCacheConfig = DefaultDCacheConfig(
        VLEN = VLEN,
        PLEN = PLEN,
        capacityBytes = dcacheCapacityBytes,
        lineBytes = dcacheLineBytes,
        ways = dcacheWays
    )
}

/** The two supported core presets. Every generator, wrapper and runner
  * defaults to GShare; `baseline` (no branch predictor) is only reachable by
  * naming it explicitly. Name parsing lives here and nowhere else.
  */
sealed abstract class CorePreset(val name: String)
object CorePreset {
    case object Gshare extends CorePreset("gshare")
    case object Baseline extends CorePreset("baseline")

    val all: Seq[CorePreset] = Seq(Gshare, Baseline)

    def fromName(name: String): CorePreset =
        all.find(_.name == name).getOrElse(
            throw new IllegalArgumentException(
                s"unsupported core preset: $name (expected gshare or baseline)"))
}

object BreezeCoreConfigs {
    def baseline(
        enableTandem: Boolean = false,
        privilegeProfile: PrivilegeProfile = PrivilegeProfile.Mcu
    ): BreezeCoreConfig =
        BreezeCoreConfig(
            useFASE = false,
            enableTandem = enableTandem,
            useGShare = false,
            privilegeProfile = privilegeProfile,
            enableCompressed = privilegeProfile.enableSupervisorUser,
            enableMmu = privilegeProfile.enableSupervisorUser
        )

    def gshare(
        enableTandem: Boolean = false,
        privilegeProfile: PrivilegeProfile = PrivilegeProfile.Mcu
    ): BreezeCoreConfig =
        BreezeCoreConfig(
            useFASE = false,
            enableTandem = enableTandem,
            useGShare = true,
            privilegeProfile = privilegeProfile,
            enableCompressed = privilegeProfile.enableSupervisorUser,
            enableMmu = privilegeProfile.enableSupervisorUser
        )

    def fromPreset(
        preset: CorePreset,
        enableTandem: Boolean,
        privilegeProfile: PrivilegeProfile
    ): BreezeCoreConfig =
        preset match {
            case CorePreset.Baseline => baseline(enableTandem, privilegeProfile)
            case CorePreset.Gshare   => gshare(enableTandem, privilegeProfile)
        }

    def fromPreset(preset: CorePreset, enableTandem: Boolean): BreezeCoreConfig =
        fromPreset(preset, enableTandem, PrivilegeProfile.Mcu)

    /** Resolve a generator/CLI core preset name to a core configuration; a
      * default-less caller always lands on GShare and `baseline` is only
      * reachable explicitly.
      */
    def fromPreset(
        preset: String,
        enableTandem: Boolean = false,
        privilegeProfile: PrivilegeProfile = PrivilegeProfile.Mcu
    ): BreezeCoreConfig =
        fromPreset(CorePreset.fromName(preset), enableTandem, privilegeProfile)
}

/** Shared single-bank L2 geometry.
  *
  * Frozen 1/2/4-core profiles: 8 ways, 32 B lines, one bank. Capacity is
  * derived from the profile: numHarts * 2 * L1D bytes.
  */
final case class L2CacheGeometry(
    capacityBytes: Int,
    lineBytes: Int = 32,
    ways: Int = 8,
    banks: Int = 1
) {
    require(banks == 1, s"L2 must be single-bank for 1/2/4-core profiles, got $banks")
    require(capacityBytes > 0 && (capacityBytes & (capacityBytes - 1)) == 0,
        s"L2 capacity must be a positive power of two, got $capacityBytes")
    require(lineBytes > 0 && (lineBytes & (lineBytes - 1)) == 0,
        s"L2 line size must be a positive power of two, got $lineBytes")
    require(ways > 0 && (ways & (ways - 1)) == 0,
        s"L2 associativity must be a positive power of two, got $ways")
    require(capacityBytes % (lineBytes * ways) == 0,
        s"L2 capacity $capacityBytes must be divisible by lineBytes*ways ${lineBytes * ways}")

    val sets: Int = capacityBytes / (lineBytes * ways)
    require(sets > 0 && (sets & (sets - 1)) == 0,
        s"L2 set count must be a positive power of two, got $sets")

    val lineWidth: Int = lineBytes * 8
    val lineOffsetWidth: Int = log2Ceil(lineBytes)
    val setIndexWidth: Int = log2Ceil(sets)
    val wayIndexWidth: Int = log2Ceil(ways)
}

/** One elaborated multicore-cluster configuration - the single configuration
  * entry point of the whole design.
  *
  * Everything is derived from (profileName, numHarts, corePreset): the L1
  * geometries come from the per-core configuration (one source of truth, no
  * parallel geometry case classes), the L2 capacity follows the frozen
  * formula numHarts * 2 * L1D bytes, and the coherence widths follow the
  * hart count. The three frozen profiles are single (1 hart), dual (2 harts)
  * and small (4 harts). 8/16-hart configurations are NOT supported and are
  * rejected by the requires below; no parser or preset accepts standard/max.
  */
final case class BreezeClusterConfig(
    profileName: String,
    numHarts: Int,
    corePreset: CorePreset = CorePreset.Gshare,
    privilegeProfile: PrivilegeProfile = PrivilegeProfile.Mcu
) {
    require(Set(1, 2, 4).contains(numHarts),
        s"cluster profile $profileName requires numHarts in {1,2,4}; got $numHarts. " +
          "8/16-core configurations are not supported by this release")

    /** Per-hart core configuration for this profile. */
    def coreCfg(enableTandem: Boolean = false): BreezeCoreConfig =
        BreezeCoreConfigs.fromPreset(corePreset, enableTandem, privilegeProfile)

    /** L1 geometries, taken from the core configuration (single source). */
    val l1i: DefaultICacheConfig = coreCfg().frontendCfg.cacheCfg
    val l1d: DefaultDCacheConfig = coreCfg().dcacheCfg

    private val l1iCapacityBytes =
        l1i.ICACHE_SET_NUM * l1i.ICACHE_WAY_NUM * l1i.ICACHE_LINE_BYTES
    require(l1iCapacityBytes == l1d.capacityBytes &&
        l1i.ICACHE_LINE_BYTES == l1d.lineBytes &&
        l1i.ICACHE_WAY_NUM == l1d.ways,
        s"L1I and L1D geometry must match for profile $profileName")

    /** Shared L2, frozen formula: numHarts * 2 * L1D bytes. */
    val l2: L2CacheGeometry =
        L2CacheGeometry(capacityBytes = numHarts * 2 * l1d.capacityBytes)
    require(l1d.lineBytes == l2.lineBytes,
        s"all cache levels must share lineBytes; L1=${l1d.lineBytes} L2=${l2.lineBytes}")

    /** max(1, ceil(log2(numHarts))) per the frozen profile rules. */
    val hartIdWidth: Int = math.max(1, log2Ceil(numHarts))
    val sharerWidth: Int = numHarts
    val txnIdWidth: Int = 2
}

/** The three publicly supported cluster profiles.
  *
  * Every public preset defaults to the GShare branch predictor. `baseline`
  * remains constructible explicitly for compatibility regressions but is never
  * the default of a preset, generator, wrapper or runner.
  */
object BreezeClusterPresets {
    val single: BreezeClusterConfig = BreezeClusterConfig("single", 1)
    val dual: BreezeClusterConfig = BreezeClusterConfig("dual", 2)
    val small: BreezeClusterConfig = BreezeClusterConfig("small", 4)

    /** Resolve a generator CLI profile name; unknown names fail fast. */
    def fromName(name: String): BreezeClusterConfig = name match {
        case "single" => single
        case "dual"   => dual
        case "small"  => small
        case other =>
            throw new IllegalArgumentException(
                s"unsupported cluster profile: $other (supported: single, dual, small; " +
                  "8/16-core profiles are not supported)"
            )
    }
}

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
    val enableMmu: Boolean = false,
    val loadUseBypass: Boolean = false
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

case class BreezeCoreConfig(
    val VLEN: Int = 64,
    val PLEN: Int = 64,
    val useFASE: Boolean = false,
    val enableTandem: Boolean = false,
    val useGShare: Boolean = true,
    val gshareGhrLength: Int = 8,
    val gshareBtbEntryNum: Int = 16,
    val privilegeProfile: PrivilegeProfile = PrivilegeProfile.Mcu,
    val enableCompressed: Boolean = false,
    val enableMmu: Boolean = false,
    val loadUseBypass: Boolean = false
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
        enableMmu = enableMmu,
        loadUseBypass = loadUseBypass
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

/** One elaborated cluster configuration - the single configuration entry
  * point of the whole design (l1d-rtl-spec §0.2).
  *
  * All memory geometry lives in `mem`; L1D, L1I, L2 and the coherence links
  * derive their constants from it (`L1DParams`, `L1IParams`,
  * `CoherenceParams`) and never declare geometry of their own. The core
  * configuration follows from the preset and privilege profile.
  */
final case class BreezeClusterConfig(
    profileName: String,
    mem: BreezeMemGeometry,
    corePreset: CorePreset = CorePreset.Gshare,
    privilegeProfile: PrivilegeProfile = PrivilegeProfile.Mcu,
    loadUseBypass: Boolean = false
) {
    val nCores: Int = mem.nCores

    /** Per-core configuration for this profile. */
    def coreCfg(enableTandem: Boolean = false): BreezeCoreConfig =
        BreezeCoreConfigs.fromPreset(corePreset, enableTandem, privilegeProfile).copy(loadUseBypass = loadUseBypass)
}

/** Named cluster profiles. `single`/`dual`/`small` are the deployment sizes;
  * `stress` and the `smoke-*` profiles are the l1d-rtl-spec §13.4 test
  * geometries. Every preset defaults to the GShare branch predictor.
  */
object BreezeClusterPresets {
    val single: BreezeClusterConfig = BreezeClusterConfig("single", BreezeMemGeometry.singleCore)
    val dual: BreezeClusterConfig = BreezeClusterConfig("dual", BreezeMemGeometry(nCores = 2))
    val small: BreezeClusterConfig = BreezeClusterConfig("small", BreezeMemGeometry.default)
    val stress: BreezeClusterConfig = BreezeClusterConfig("stress", BreezeMemGeometry.stress)
    val smokeL2w4: BreezeClusterConfig = BreezeClusterConfig("smoke-l2w4", BreezeMemGeometry.l2FourWay)
    val smokeL1dw2: BreezeClusterConfig = BreezeClusterConfig("smoke-l1dw2", BreezeMemGeometry.l1dTwoWay)
    val smoke1core: BreezeClusterConfig = BreezeClusterConfig("smoke-1core", BreezeMemGeometry.singleCore)

    val all: Seq[BreezeClusterConfig] = Seq(single, dual, small, stress, smokeL2w4, smokeL1dw2, smoke1core)

    /** Resolve a generator CLI profile name; unknown names fail fast. */
    def fromName(name: String): BreezeClusterConfig =
        all.find(_.profileName == name).getOrElse(
            throw new IllegalArgumentException(
                s"unsupported cluster profile: $name (supported: ${all.map(_.profileName).mkString(", ")})"))
}

package flow.config

import flow.mmu.sv39.{Sv39MmuParams, Sv39TlbParams}

/** Resource-rich scalar single hart. These are elaboration parameters;
  * the KCU105 target adds SD coherent DMA and optional tandem/ILA observation.
  * Keep this definition independent of the capacity-tuned quad preset.
  */
object BreezeSingleFullConfig {
  val cluster: BreezeClusterConfig = BreezeClusterConfig(
    profileName = "single",
    mem = BreezeMemGeometry(
      nCores = 1,
      lineBytes = 32,
      l1Sets = 128,
      l1dWays = 4,
      l1iWays = 4,
      l1dMshrs = 1,
      l2Ways = 8,
      l2BytesPerCore = 65536,
      l2Slots = 2,
      paddrBits = 32),
    corePreset = CorePreset.Gshare,
    privilegeProfile = PrivilegeProfile.Linux,
    loadUseBypass = false,
    coprocessorStub = None,
    rvv = None,
    mmu = Sv39MmuParams(
      asidBits = 16,
      itlb = Sv39TlbParams(sets = 8, ways = 4, superpages = 4),
      dtlb = Sv39TlbParams(sets = 8, ways = 4, superpages = 4),
      wcUpperSets = 1,
      wcUpperWays = 4,
      wcMiddleSets = 2,
      wcMiddleWays = 4),
    fpTagEntries = 32)
}

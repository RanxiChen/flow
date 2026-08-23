package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.{BreezeClusterPresets, PrivilegeProfile}

/** Emit the one fixed EP4CE10 resource-probe configuration.
  *
  * This is intentionally not a configurable product generator. It preserves
  * the existing single-hart cache/coherence/Wishbone structure and changes
  * only cache capacity: 2 KiB L1I, 2 KiB L1D and 4 KiB L2. The ISA is fixed
  * to RV64I + Zicsr + Zifencei; M/A/F/D units are not elaborated.
  */
object GenerateBreezeTinyFpga extends App {
    require(args.isEmpty, "GenerateBreezeTinyFpga takes no arguments")

    private val clusterCfg = BreezeClusterPresets.tinyFpga
    private val targetDir =
        os.pwd / "build" / "rtl" / "cluster" / "tiny-fpga" / "tiny-fpga"

    // This directory contains generated artifacts only. Recreate it so a
    // previous full-backend probe can never leak stale FP/M RTL into Tiny's
    // filelist after those modules stop being elaborated.
    if (os.exists(targetDir)) os.remove.all(targetDir)

    println(
        s"[Breeze Tiny FPGA RTL] profile=${clusterCfg.profileName} " +
          s"harts=${clusterCfg.numHarts} target_dir=$targetDir"
    )
    ChiselStage.emitSystemVerilogFile(
        new BreezeMulticoreClusterWishbone(clusterCfg, enableTandem = false),
        Array("--target-dir", targetDir.toString),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "-default-layer-specialization=enable"
        )
    )

    private val svFiles = os.list(targetDir).filter(_.ext == "sv").map(_.last).sorted
    require(svFiles.nonEmpty, s"no SystemVerilog files emitted into $targetDir")
    os.write.over(targetDir / "filelist.f", svFiles.mkString("", "\n", "\n"))

    private val l1iBytes = clusterCfg.l1i.ICACHE_SET_NUM *
        clusterCfg.l1i.ICACHE_WAY_NUM * clusterCfg.l1i.ICACHE_LINE_BYTES
    private val profileText =
        s"""profile=${clusterCfg.profileName}
           |numHarts=${clusterCfg.numHarts}
           |l1iBytes=$l1iBytes
           |l1dBytes=${clusterCfg.l1d.capacityBytes}
           |l2Bytes=${clusterCfg.l2.capacityBytes}
           |lineBytes=${clusterCfg.l1d.lineBytes}
           |l1Ways=${clusterCfg.l1d.ways}
           |l2Ways=${clusterCfg.l2.ways}
           |corePreset=${clusterCfg.corePreset.name}
           |privilegeProfile=${PrivilegeProfile.Mcu.name}
           |rtlMode=production
           |tandem=false
           |isa=rv64i_zicsr_zifencei
           |resourceProbe=tiny-fixed-isa
           |""".stripMargin
    os.write.over(targetDir / "cluster-profile.txt", profileText)
    os.write.over(targetDir / "core-preset.txt", "tiny-fpga\n")
    os.write.over(targetDir / "privilege-profile.txt", "mcu\n")
}

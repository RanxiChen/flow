package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.BreezeClusterPresets

/** Cluster RTL generator.
  *
  *   sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone single'
  *   sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone dual'
  *   sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone small'
  *   sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone single baseline'
  *
  * The optional second argument is the core preset, defaulting to gshare.
  * Unknown profiles or presets exit non-zero. Outputs land in
  * build/rtl/cluster/<profile>/<preset>/ with BreezeMulticoreClusterWishbone.sv,
  * filelist.f and cluster-profile.txt; presets never overwrite each other.
  */
object GenerateBreezeMulticoreClusterWishbone extends App {
    require(args.length >= 1 && args.length <= 2,
        "usage: GenerateBreezeMulticoreClusterWishbone <single|dual|small> [gshare|baseline]")

    private val profile = BreezeClusterPresets.fromName(args(0))
    private val corePreset = args.lift(1).getOrElse("gshare")
    require(corePreset == "gshare" || corePreset == "baseline",
        s"unsupported core preset: $corePreset (expected gshare or baseline)")

    private val clusterCfg = profile.copy(corePreset = corePreset)
    private val targetDir = os.pwd / "build" / "rtl" / "cluster" / profile.profileName / corePreset

    println(
        s"[BreezeCluster RTL] profile=${profile.profileName} harts=${profile.numHarts} " +
          s"core_preset=$corePreset target_dir=$targetDir"
    )
    ChiselStage.emitSystemVerilogFile(
        new BreezeMulticoreClusterWishbone(clusterCfg, enableTandem = true),
        Array("--target-dir", targetDir.toString),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "-default-layer-specialization=enable"
        )
    )

    // The split SystemVerilog manifest is generated from what firtool actually
    // emitted, so the LiteX wrapper always loads the exact files of this run.
    private val svFiles = os.list(targetDir).filter(_.ext == "sv").map(_.last).sorted
    require(svFiles.nonEmpty, s"no SystemVerilog files emitted into $targetDir")
    os.write.over(targetDir / "filelist.f", svFiles.mkString("", "\n", "\n"))

    private val profileText =
        s"""profile=${profile.profileName}
           |numHarts=${profile.numHarts}
           |l1iBytes=${profile.l1i.capacityBytes}
           |l1dBytes=${profile.l1d.capacityBytes}
           |l2Bytes=${profile.l2.capacityBytes}
           |lineBytes=${profile.l1i.lineBytes}
           |l1Ways=${profile.l1i.ways}
           |l2Ways=${profile.l2.ways}
           |corePreset=$corePreset
           |""".stripMargin
    os.write.over(targetDir / "cluster-profile.txt", profileText)
    os.write.over(targetDir / "core-preset.txt", s"$corePreset\n")
}

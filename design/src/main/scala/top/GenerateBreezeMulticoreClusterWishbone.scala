package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.{BreezeClusterPresets, CorePreset}

/** Cluster RTL generator - the single RTL entry point of the design.
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

    private val corePreset = CorePreset.fromName(args.lift(1).getOrElse("gshare"))
    private val clusterCfg =
        BreezeClusterPresets.fromName(args(0)).copy(corePreset = corePreset)
    private val targetDir =
        os.pwd / "build" / "rtl" / "cluster" / clusterCfg.profileName / corePreset.name

    println(
        s"[BreezeCluster RTL] profile=${clusterCfg.profileName} harts=${clusterCfg.numHarts} " +
          s"core_preset=${corePreset.name} target_dir=$targetDir"
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
    private val designRoot = os.pwd
    private val flowRoot = designRoot / os.up
    private val cvfpuRoot = flowRoot / "third_party" / "cvfpu"
    private val fpManifest = designRoot / "src" / "main" / "resources" /
        "vsrc" / "fpnew" / "cvfpu-files.f"
    private val fpWrapper = designRoot / "src" / "main" / "resources" /
        "vsrc" / "fpnew" / "FlowFpnewWrapper.sv"
    require(os.isDir(cvfpuRoot), s"CVFPU submodule is missing: $cvfpuRoot")
    require(os.isFile(fpManifest), s"CVFPU manifest is missing: $fpManifest")
    require(os.isFile(fpWrapper), s"FPnew wrapper is missing: $fpWrapper")
    // HasBlackBoxPath copies the same FPnew sources into the elaboration
    // directory for ChiselSim.  LiteX deliberately consumes the originals
    // below, so exclude those copies from the firtool-emitted design list.
    private val fpSourceNames = os.read.lines(fpManifest).map(_.trim).filter(line =>
        line.nonEmpty && !line.startsWith("#") && !line.startsWith("+incdir+")
    ).map(line => os.RelPath(line).last).toSet + fpWrapper.last
    private val svFiles = os.list(targetDir).filter(path =>
        path.ext == "sv" && !fpSourceNames.contains(path.last)
    ).map(_.last).sorted
    require(svFiles.nonEmpty, s"no SystemVerilog files emitted into $targetDir")
    private val fpEntries = os.read.lines(fpManifest).map(_.trim).filter(line =>
        line.nonEmpty && !line.startsWith("#")).map { line =>
        if (line.startsWith("+incdir+")) {
            s"+incdir+${(cvfpuRoot / os.RelPath(line.stripPrefix("+incdir+"))).toString}"
        } else {
            val source = cvfpuRoot / os.RelPath(line)
            require(os.isFile(source), s"CVFPU source is missing: $source")
            source.toString
        }
    }
    private val completeFilelist = fpEntries ++ Seq(fpWrapper.toString) ++ svFiles
    os.write.over(targetDir / "filelist.f", completeFilelist.mkString("", "\n", "\n"))

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
           |corePreset=${corePreset.name}
           |""".stripMargin
    os.write.over(targetDir / "cluster-profile.txt", profileText)
    os.write.over(targetDir / "core-preset.txt", s"${corePreset.name}\n")
}

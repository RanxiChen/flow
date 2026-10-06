package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.{BreezeClusterPresets, BreezeMemGeometry, CorePreset, PrivilegeProfile}

/** Native memory cluster with a LiteX Wishbone shell.
  * sbt 'runMain flow.top.GenerateBreezeCluster single gshare linux dma tandem'
  * Outputs use a separate mem-cluster directory; existing board evidence is
  * not overwritten. FASE/debug trace pin migration needs its own contract.
  */
object GenerateBreezeCluster extends App {
    require(args.nonEmpty && args.length <= 5,
        "usage: GenerateBreezeCluster <single|dual|small|stress> [gshare|baseline] [mcu|linux] [dma] [tandem]")
    require(args.drop(3).forall(Set("dma", "tandem")), "unsupported optional feature (FASE contract pending)")
    private val corePreset = CorePreset.fromName(args.lift(1).getOrElse("gshare"))
    private val privilegeProfile = PrivilegeProfile.fromName(args.lift(2).getOrElse("mcu"))
    private val withDma = args.drop(3).contains("dma")
    private val enableTandem = args.drop(3).contains("tandem")
    private val mem = if(args(0) == "stress") BreezeMemGeometry.stress else
        BreezeMemGeometry(nCores = BreezeClusterPresets.fromName(args(0)).numHarts)
    private val coreCfg = flow.config.BreezeCoreConfigs.fromPreset(corePreset, enableTandem, privilegeProfile)
    private val targetDir = os.pwd / "build" / "rtl" / "mem-cluster" /
        args(0) / corePreset.name / privilegeProfile.name /
        (if(withDma) "dma" else "cpu") / (if(enableTandem) "tandem" else "production")
    ChiselStage.emitSystemVerilogFile(
        new BreezeClusterWishbone(mem, coreCfg, withDma),
        Array("--target-dir", targetDir.toString),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable"))
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
    ).map(line => os.RelPath(line).last).toSet ++
        Set(fpWrapper.last, "FlowFpnewSources.sv")
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

    os.write.over(targetDir / "cluster-profile.txt",
        s"nCores=${mem.nCores}\nlineBytes=${mem.lineBytes}\nl1Sets=${mem.l1Sets}\nl1dWays=${mem.l1dWays}\nl1iWays=${mem.l1iWays}\nl2Ways=${mem.l2Ways}\nl2BytesPerCore=${mem.l2BytesPerCore}\ndma=$withDma\ntandem=$enableTandem\n")
}

package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.BreezeBuildOptions

/** AXI SoC generator; optional debug is passive and enables retirement traces. */
object GenerateBreezeCluster extends App {
    private val options = BreezeBuildOptions.parse(args.toSeq)
    private val cfg = options.config
    private val corePreset = cfg.corePreset
    private val privilegeProfile = cfg.privilegeProfile
    private val debug = options.debug
    private val enableTandem = options.enableTandem
    private val hangThreshold = sys.env.getOrElse("BREEZE_HANG_CYCLES", "10000000").toInt
    require(hangThreshold > 0, "BREEZE_HANG_CYCLES must be positive")
    private val mem = cfg.mem
    private val baseDir = os.pwd / "build" / "rtl" / "axi-cluster" /
        cfg.profileName / corePreset.name / privilegeProfile.name /
        (if(enableTandem) "tandem" else "production") / (if(debug) "fpga-debug" else "cpu")
    private val targetDir = if (cfg.coherentDma) baseDir / "coherent-dma" else baseDir
    // Keep older generated artifacts intact, but build this manifest only
    // from a fresh emission. Directory scans must not re-admit deleted RTL.
    private val emittedDir = targetDir / "emitted" / java.util.UUID.randomUUID().toString
    ChiselStage.emitSystemVerilogFile(
        new BreezeClusterAxi(cfg, enableTandem, debug, hangThreshold),
        Array("--target-dir", emittedDir.toString, "--full-stacktrace"),
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
    private val svFiles = os.list(emittedDir).filter(path =>
        path.ext == "sv" && !fpSourceNames.contains(path.last)
    ).map(_.relativeTo(targetDir).toString).sorted
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

    private val platformBytes = os.read.bytes(flowRoot / "config" / "breeze_mcu_platform.json")
    private val platformHash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(platformBytes).map(b => f"${b & 0xff}%02x").mkString
    os.write.over(targetDir / "cluster-profile.txt",
        s"hangThresholdCycles=$hangThreshold\nbus=axi\nprofile=${cfg.profileName}\npreset=${corePreset.name}\nprivilege=${privilegeProfile.name}\ntandem=$enableTandem\ndebug=$debug\nplatformSha256=$platformHash\nnCores=${mem.nCores}\nlineBytes=${mem.lineBytes}\nl1Sets=${mem.l1Sets}\nl1dWays=${mem.l1dWays}\nl1iWays=${mem.l1iWays}\nl2Ways=${mem.l2Ways}\nl2BytesPerCore=${mem.l2BytesPerCore}\nl2Slots=${mem.l2Slots}\nidBits=${flow.coherence.CoherenceParams(mem).slotBits}\n")
    os.write.append(targetDir / "cluster-profile.txt", s"coherentDma=${cfg.coherentDma}\nrvv=false\nmemoryBits=64\n" +
      (if (cfg.zve.nonEmpty) s"zve=zve32f\nvlen=${cfg.zve.get.vrf.vlen}\nvectorL2Ids=${mem.vectorL2Ids}\n" else ""))
    os.write.append(targetDir / "cluster-profile.txt",
        s"fpTagEntries=${cfg.fpTagEntries}\n")
    os.write.append(targetDir / "cluster-profile.txt",
        s"itlbSets=${cfg.mmu.itlb.sets}\nitlbWays=${cfg.mmu.itlb.ways}\nitlbSuperpages=${cfg.mmu.itlb.superpages}\n" +
        s"dtlbSets=${cfg.mmu.dtlb.sets}\ndtlbWays=${cfg.mmu.dtlb.ways}\ndtlbSuperpages=${cfg.mmu.dtlb.superpages}\n" +
        s"wcUpperSets=${cfg.mmu.wcUpperSets}\nwcUpperWays=${cfg.mmu.wcUpperWays}\n" +
        s"wcMiddleSets=${cfg.mmu.wcMiddleSets}\nwcMiddleWays=${cfg.mmu.wcMiddleWays}\n")
}

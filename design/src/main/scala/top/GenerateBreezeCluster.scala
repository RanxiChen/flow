package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.{BreezeClusterPresets, CorePreset, PrivilegeProfile}

/** AXI SoC generator; optional debug is passive and enables retirement traces. */
object GenerateBreezeCluster extends App {
    require(args.length >= 3 && args.length <= 5,
        "usage: GenerateBreezeCluster <single|small> <gshare|baseline> <mcu|linux> [tandem] [fpga-debug]")
    require(Set("single", "small").contains(args(0)), "未支持的 SoC profile")
    require(args.drop(3).forall(Set("tandem", "fpga-debug")), "未支持：DMA/FASE 或未知选项")
    require(args.drop(3).distinct.length == args.drop(3).length, "duplicate optional feature")
    private val corePreset = CorePreset.fromName(args(1))
    private val privilegeProfile = PrivilegeProfile.fromName(args(2))
    private val debug = args.drop(3).contains("fpga-debug")
    // Generation parameter without extending the frozen positional CLI.
    private val hangThreshold = sys.env.getOrElse("BREEZE_HANG_CYCLES", "10000000").toInt
    require(hangThreshold > 0, "BREEZE_HANG_CYCLES must be positive")
    private val enableTandem = args.drop(3).contains("tandem") || debug
    private val cfg = BreezeClusterPresets.fromName(args(0))
        .copy(corePreset = corePreset, privilegeProfile = privilegeProfile)
    private val mem = cfg.mem
    private val targetDir = os.pwd / "build" / "rtl" / "axi-cluster" /
        args(0) / corePreset.name / privilegeProfile.name /
        (if(enableTandem) "tandem" else "production") / (if(debug) "fpga-debug" else "cpu")
    ChiselStage.emitSystemVerilogFile(
        new BreezeClusterAxi(cfg, enableTandem, debug, hangThreshold),
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

    private val platformBytes = os.read.bytes(flowRoot / "config" / "breeze_mcu_platform.json")
    private val platformHash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(platformBytes).map(b => f"${b & 0xff}%02x").mkString
    os.write.over(targetDir / "cluster-profile.txt",
        s"hangThresholdCycles=$hangThreshold\nbus=axi\nprofile=${cfg.profileName}\npreset=${corePreset.name}\nprivilege=${privilegeProfile.name}\ntandem=$enableTandem\ndebug=$debug\nplatformSha256=$platformHash\nnCores=${mem.nCores}\nlineBytes=${mem.lineBytes}\nl1Sets=${mem.l1Sets}\nl1dWays=${mem.l1dWays}\nl1iWays=${mem.l1iWays}\nl2Ways=${mem.l2Ways}\nl2BytesPerCore=${mem.l2BytesPerCore}\nidBits=${flow.coherence.CoherenceParams(mem).slotBits}\n")
}

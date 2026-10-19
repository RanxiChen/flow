package flow.top

import flow.config.BreezeBuildOptions

/** Filesystem inputs and LiteX metadata for the cluster generator; no RTL elaboration. */
private[top] object BreezeBuildArtifacts {
    final case class Inputs(fpEntries: Seq[String], fpSourceNames: Set[String], platformHash: String)

    /** Check every external input before starting the expensive Chisel stage.
      * Preserve manifest order: packages must precede their consumers.
      */
    def prepare(designRoot: os.Path): Inputs = {
        val flowRoot = designRoot / os.up
        val cvfpuRoot = flowRoot / "third_party" / "cvfpu"
        val fpRoot = designRoot / "src" / "main" / "resources" / "vsrc" / "fpnew"
        val manifest = fpRoot / "cvfpu-files.f"
        val wrapper = fpRoot / "FlowFpnewWrapper.sv"
        val platform = flowRoot / "config" / "breeze_platform.json"
        require(os.isDir(cvfpuRoot), s"CVFPU submodule is missing: $cvfpuRoot")
        for (path <- Seq(manifest, wrapper, platform))
            require(os.isFile(path), s"build input is missing: $path")

        val lines = os.read.lines(manifest).map(_.trim).filter(line =>
            line.nonEmpty && !line.startsWith("#"))
        val entries = lines.map { line =>
            if (line.startsWith("+incdir+")) {
                val include = cvfpuRoot / os.RelPath(line.stripPrefix("+incdir+"))
                require(os.isDir(include), s"CVFPU include directory is missing: $include")
                s"+incdir+$include"
            } else {
                val source = cvfpuRoot / os.RelPath(line)
                require(os.isFile(source), s"CVFPU source is missing: $source")
                source.toString
            }
        }
        val sourceNames = lines.filterNot(_.startsWith("+incdir+"))
            .map(line => os.RelPath(line).last).toSet ++
            Set(wrapper.last, "FlowFpnewSources.sv")
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(os.read.bytes(platform)).map(b => f"${b & 0xff}%02x").mkString
        Inputs(entries :+ wrapper.toString, sourceNames, hash)
    }

    def targetDirectory(designRoot: os.Path, options: BreezeBuildOptions): os.Path = {
        val cfg = options.config
        val base = designRoot / "build" / "rtl" / "axi-cluster" /
            cfg.profileName / cfg.corePreset.name / cfg.privilegeProfile.name /
            (if (options.enableTandem) "tandem" else "production") /
            (if (options.debug) "fpga-debug" else "cpu")
        if (cfg.coherentDma) base / "coherent-dma" else base
    }

    def filelist(inputs: Inputs, targetDir: os.Path, emittedDir: os.Path): String = {
        // ChiselSim copies FPnew sources into the emission directory. LiteX uses
        // the originals, so exclude those copies and scan only this emission.
        val generated = os.list(emittedDir).filter(path =>
            os.isFile(path) && path.ext == "sv" && !inputs.fpSourceNames.contains(path.last)
        ).map(_.relativeTo(targetDir).toString).sorted
        require(generated.nonEmpty, s"no SystemVerilog files emitted into $emittedDir")
        (inputs.fpEntries ++ generated).mkString("", "\n", "\n")
    }

    def profile(options: BreezeBuildOptions, hangThreshold: Int, platformHash: String): String = {
        val cfg = options.config
        val mem = cfg.mem
        val mmu = cfg.mmu
        val fields = Seq[(String, Any)](
            "hangThresholdCycles" -> hangThreshold,
            "bus" -> "axi",
            "profile" -> cfg.profileName,
            "preset" -> cfg.corePreset.name,
            "privilege" -> cfg.privilegeProfile.name,
            "tandem" -> options.enableTandem,
            "debug" -> options.debug,
            "platformSha256" -> platformHash,
            "nCores" -> mem.nCores,
            "lineBytes" -> mem.lineBytes,
            "l1Sets" -> mem.l1Sets,
            "l1dWays" -> mem.l1dWays,
            "l1iWays" -> mem.l1iWays,
            "l2Ways" -> mem.l2Ways,
            "l2BytesPerCore" -> mem.l2BytesPerCore,
            "l2Slots" -> mem.l2Slots,
            "idBits" -> flow.coherence.CoherenceParams(mem).slotBits,
            "coherentDma" -> cfg.coherentDma,
            "rvv" -> false,
            "memoryBits" -> 64
        ) ++ cfg.zve.toSeq.flatMap(zve => Seq[(String, Any)](
            "zve" -> "zve32f", "vlen" -> zve.vrf.vlen, "vectorL2Ids" -> mem.vectorL2Ids
        )) ++ Seq[(String, Any)](
            "fpTagEntries" -> cfg.fpTagEntries,
            "itlbSets" -> mmu.itlb.sets,
            "itlbWays" -> mmu.itlb.ways,
            "itlbSuperpages" -> mmu.itlb.superpages,
            "dtlbSets" -> mmu.dtlb.sets,
            "dtlbWays" -> mmu.dtlb.ways,
            "dtlbSuperpages" -> mmu.dtlb.superpages,
            "wcUpperSets" -> mmu.wcUpperSets,
            "wcUpperWays" -> mmu.wcUpperWays,
            "wcMiddleSets" -> mmu.wcMiddleSets,
            "wcMiddleWays" -> mmu.wcMiddleWays
        )
        fields.map { case (key, value) => s"$key=$value" }.mkString("", "\n", "\n")
    }

    def publish(inputs: Inputs, targetDir: os.Path, emittedDir: os.Path,
                options: BreezeBuildOptions, hangThreshold: Int): Unit = {
        // Finish validation/serialization before replacing either output.
        val sources = filelist(inputs, targetDir, emittedDir)
        val marker = profile(options, hangThreshold, inputs.platformHash)
        os.write.over(targetDir / "filelist.f", sources)
        os.write.over(targetDir / "cluster-profile.txt", marker)
    }
}

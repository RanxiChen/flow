package flow.config

/** Public generator options. GShare/Linux are fixed, not selectable variants. */
final case class BreezeBuildOptions(
    profile: String,
    tandem: Boolean = false,
    debug: Boolean = false,
    coherentDma: Boolean = false
) {
    private val selected = BreezeClusterPresets.deployment.find(_.profileName == profile)
        .getOrElse(throw new IllegalArgumentException(s"unsupported deployment profile: $profile"))
    val enableTandem: Boolean = tandem || debug
    val config: BreezeClusterConfig = selected.copy(
        corePreset = CorePreset.Gshare,
        privilegeProfile = PrivilegeProfile.Linux,
        coherentDma = coherentDma
    )
}

object BreezeBuildOptions {
    val usage = "usage: GenerateBreezeCluster <single|small|single-zve32f> [tandem] [fpga-debug] [coherent-dma]"
    private val features = Set("tandem", "fpga-debug", "coherent-dma")

    def parse(args: Seq[String]): BreezeBuildOptions = {
        require(args.nonEmpty, usage)
        val flags = args.tail
        require(flags.forall(features), s"unsupported option; $usage")
        require(flags.distinct.length == flags.length, "duplicate optional feature")
        BreezeBuildOptions(args.head,
            tandem = flags.contains("tandem"),
            debug = flags.contains("fpga-debug"),
            coherentDma = flags.contains("coherent-dma"))
    }
}

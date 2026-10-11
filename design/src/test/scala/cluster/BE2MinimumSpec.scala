package flow.cluster

import java.nio.file.Files
import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** User-selected minimum gate, separate from the unchanged full ISA suites. */
class BE2MinimumSpec extends AnyFreeSpec with Matchers {
  for (profile <- Seq("single", "small"))
  s"minimum RV64I M A C and CSR programs retain their original pass signatures ($profile)" in {
    val root = ClusterSim.repoRoot
    val names = Seq("rv64ui-p-add", "rv64ui-p-lw", "rv64ui-p-sw", "rv64ui-p-beq",
      "rv64um-p-mul", "rv64um-p-div", "rv64ua-p-amoadd_d", "rv64ua-p-lrsc",
      "rv64uc-p-rvc", "rv64mi-p-csr")
    val cfg = BreezeClusterPresets.fromName(profile).copy(privilegeProfile = PrivilegeProfile.Linux)
    val runs = names.map { name =>
      val elf = root.resolve(s"design/build/riscv-tests/$name")
      require(Files.isRegularFile(elf), s"missing minimum-gate ELF: $elf")
      ClusterRunConfig(cfg, elf)
    }
    // ISA p-environment parks the other harts; the separate multi-hart
    // system programs exercise coherence, atomics, interrupts and SFENCE.
    val results = ClusterSim.runMany(runs, s"be2-minimum-$profile")
    results.size mustBe names.size
    for ((name, (_, result)) <- names.zip(results)) {
      info(s"$name passed=${result.passed} cycles=${result.cycles} retired=${result.retired.mkString("/")}")
      withClue(s"$name: ${result.reason}") { result.passed mustBe true }
      result.tohost.head mustBe Some(BigInt(1))
    }
  }
}

"""Test-only baseline adapter for the original MCU Fibonacci finish ABI.
Defaults preserve all existing tohost=1 checks; production sources untouched.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1])
p = root/'design/src/test/scala/cluster/ClusterSim.scala'
s = p.read_text()
old = '    traceLog: Boolean = false)'
assert s.count(old) == 1
s = s.replace(old,'    traceLog: Boolean = false,\n    tohostSymbol: String = "tohost",\n    passValue: BigInt = 1)')
s = s.replace('image.symbols.getOrElse("tohost",','image.symbols.getOrElse(c.tohostSymbol,')
s = s.replace('if (v != 1) verdict','if (v != c.passValue) verdict')
s = s.replace('tohost(h).contains(BigInt(1))','tohost(h).contains(c.passValue)')
p.write_text(s)
(root/'design/src/test/scala/cluster/BE2BaselinePerformanceSpec.scala').write_text('''package flow.cluster
import flow.config.{BreezeClusterPresets,PrivilegeProfile}
import java.nio.file.Paths
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
class BE2BaselinePerformanceSpec extends AnyFreeSpec with Matchers {
  "P1 baseline with original Fibonacci runtime pass marker" in {
    val root=ClusterSim.repoRoot
    val cfg=BreezeClusterPresets.single.copy(privilegeProfile=PrivilegeProfile.Linux)
    val names=Seq("lrsc_amo_single","trap_misc","mmio_console","fibonacci")
    val runs=names.take(3).map(n=>ClusterRunConfig(cfg,root.resolve(s"tests/cluster/build/$n.elf"))) :+
      ClusterRunConfig(cfg,Paths.get(sys.env("BE2_FIB_ELF")),resetAddr=BigInt(0x10000000),
        tohostSymbol="__breeze_result",passValue=BigInt("425245455a455001",16))
    val results=ClusterSim.runMany(runs,"be2-p1-baseline")
    for((name,(_,result))<-names.zip(results)) {
      withClue(s"$name: ${result.reason}") { result.passed mustBe true }
      if(name=="mmio_console") result.console mustBe "breeze cluster console ok\\nX\\n"
      info(s"P1 $name cycles=${result.cycles} retired=${result.retired.mkString("/")}")
    }
  }
}
''')

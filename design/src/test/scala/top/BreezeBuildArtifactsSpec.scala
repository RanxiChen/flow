package flow.top

import flow.config.BreezeBuildOptions
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeBuildArtifactsSpec extends AnyFreeSpec with Matchers {
    private def fixture(test: (os.Path, os.Path) => Unit): Unit = {
        val root = os.temp.dir(prefix = "breeze-build-artifacts-")
        try {
            val design = root / "design"
            val fp = design / "src" / "main" / "resources" / "vsrc" / "fpnew"
            val cvfpu = root / "third_party" / "cvfpu"
            os.makeDir.all(fp)
            os.makeDir.all(cvfpu / "include")
            os.write(fp / "cvfpu-files.f",
                "# ordered dependency list\n\n+incdir+include\npkg.sv\nunit.v\n")
            os.write(fp / "FlowFpnewWrapper.sv", "// wrapper\n")
            os.write(cvfpu / "pkg.sv", "// package\n")
            os.write(cvfpu / "unit.v", "// unit\n")
            os.write(root / "config" / "breeze_mcu_platform.json", "{}", createFolders = true)
            test(design, cvfpu)
        } finally os.remove.all(root)
    }

    "preflight preserves dependency order, Verilog sources and the raw platform hash" in fixture { (design, cvfpu) =>
        val inputs = BreezeBuildArtifacts.prepare(design)
        inputs.fpEntries.take(3) mustBe Seq(s"+incdir+${cvfpu / "include"}",
            (cvfpu / "pkg.sv").toString, (cvfpu / "unit.v").toString)
        inputs.fpEntries.last must endWith ("/FlowFpnewWrapper.sv")
        inputs.platformHash mustBe "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    }

    "missing sources, include directories, wrapper or platform fail before creating outputs" in {
        for (relative <- Seq("third_party/cvfpu/include", "third_party/cvfpu/unit.v",
            "design/src/main/resources/vsrc/fpnew/FlowFpnewWrapper.sv",
            "config/breeze_mcu_platform.json")) fixture { (design, _) =>
            os.remove.all((design / os.up) / os.RelPath(relative))
            val error = intercept[IllegalArgumentException](BreezeBuildArtifacts.prepare(design))
            error.getMessage must include ("missing")
            os.exists(design / "build") mustBe false
        }
    }

    "filelist ignores stale runs and duplicate FP copies and keeps generated sources sorted" in fixture { (design, _) =>
        val inputs = BreezeBuildArtifacts.prepare(design)
        val target = design / "output"
        val emitted = target / "emitted" / "current"
        os.makeDir.all(emitted)
        os.write(target / "stale.sv", "// stale")
        os.write(target / "emitted" / "old" / "old.sv", "// stale", createFolders = true)
        for (name <- Seq("Z.sv", "A.sv", "pkg.sv", "FlowFpnewWrapper.sv", "FlowFpnewSources.sv"))
            os.write(emitted / name, "// source")
        os.makeDir(emitted / "directory.sv")
        val lines = BreezeBuildArtifacts.filelist(inputs, target, emitted).linesIterator.toSeq
        lines mustBe inputs.fpEntries ++ Seq("emitted/current/A.sv", "emitted/current/Z.sv")
    }

    "an empty emission leaves existing manifests untouched" in fixture { (design, _) =>
        val inputs = BreezeBuildArtifacts.prepare(design)
        val emitted = design / "output" / "emitted" / "empty"
        os.makeDir.all(emitted)
        val target = emitted / os.up / os.up
        os.write(target / "filelist.f", "previous sources\n")
        os.write(target / "cluster-profile.txt", "previous marker\n")
        an[IllegalArgumentException] must be thrownBy BreezeBuildArtifacts.publish(
            inputs, target, emitted, BreezeBuildOptions("single"), 100)
        os.read(target / "filelist.f") mustBe "previous sources\n"
        os.read(target / "cluster-profile.txt") mustBe "previous marker\n"
    }

    "all public profiles and feature combinations retain the LiteX path and marker contract" in {
        for (name <- Seq("single", "small", "single-zve32f");
             tandem <- Seq(false, true); debug <- Seq(false, true); dma <- Seq(false, true)) {
            val options = BreezeBuildOptions(name, tandem, debug, dma)
            val text = BreezeBuildArtifacts.profile(options, 123, "platform-hash")
            val pairs = text.linesIterator.map(_.split("=", 2)).map(p => p(0) -> p(1)).toSeq
            val marker = pairs.toMap
            pairs.size mustBe marker.size
            marker("profile") mustBe name
            marker("preset") mustBe "gshare"
            marker("privilege") mustBe "linux"
            marker("tandem") mustBe (tandem || debug).toString
            marker("debug") mustBe debug.toString
            marker("coherentDma") mustBe dma.toString
            marker("platformSha256") mustBe "platform-hash"
            marker("hangThresholdCycles") mustBe "123"
            marker("rvv") mustBe "false"
            marker("memoryBits") mustBe "64"
            marker("fpTagEntries") mustBe "32"
            marker.contains("zve") mustBe (name == "single-zve32f")
            if (name == "single-zve32f") {
                marker("zve") mustBe "zve32f"
                marker("vlen") mustBe "512"
                marker("vectorL2Ids") mustBe "8"
            }
            val suffix = s"$name/gshare/linux/${if (tandem || debug) "tandem" else "production"}/" +
                (if (debug) "fpga-debug" else "cpu") + (if (dma) "/coherent-dma" else "")
            BreezeBuildArtifacts.targetDirectory(os.root / "design", options).toString mustBe
                s"/design/build/rtl/axi-cluster/$suffix"
        }
    }
}

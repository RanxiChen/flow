ThisBuild / scalaVersion     := "2.13.16"
ThisBuild / version          := "0.1.0"

val chiselVersion = "7.0.0"

lazy val root = (project in file("."))
  .settings(
    name := "flow",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % "test",
      "com.lihaoyi" %% "os-lib" % "0.11.6",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.2"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
    // Chisel 7.0.0's svsim backend has no extra-argument/control-file setting.
    // Scope the CVFPU waiver to ChiselSim test processes, preserving all of
    // svsim's arguments and the real Verilator's exit status.
    Test / fork := true,
    Test / envVars ++= {
      val inheritedPath = sys.env.getOrElse("PATH", "")
      val realVerilator = inheritedPath.split(java.io.File.pathSeparator).iterator
        .map(dir => file(dir) / "verilator")
        .find(_.canExecute)
        .getOrElse(sys.error("Verilator is required for ChiselSim tests"))
        .getCanonicalPath
      val shimDir = (baseDirectory.value / "project" / "chiselsim-verilator").getCanonicalPath
      Map(
        "PATH" -> (shimDir + java.io.File.pathSeparator + inheritedPath),
        "FLOW_CHISELSIM_VERILATOR_REAL" -> realVerilator
      )
    },
  )

// Canonical BreezeCore developer entry points. `build` checks both production
// and test Scala sources without running tests; `elaborate` emits the single
// official SoC-facing RTL top through a dedicated App.
addCommandAlias("build", ";Compile / compile;Test / compile")
addCommandAlias(
  "elaborate",
  "Compile / runMain flow.top.GenerateBreezeCoreWishbone"
)

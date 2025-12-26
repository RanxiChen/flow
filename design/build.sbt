ThisBuild / scalaVersion     := "2.13.16"
ThisBuild / version          := "0.1.0"

val chiselVersion = "7.0.0"

lazy val root = (project in file("."))
  .settings(
    name := "flow",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % "test",
      "com.lihaoyi" %% "os-lib" % "0.11.6"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )

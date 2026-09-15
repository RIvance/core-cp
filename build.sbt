import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.8.4"

lazy val root = project.in(file("."))
  .aggregate(visualizer)
  .settings(
    name := "core-cp",
    idePackagePrefix := Some("cp"),
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
      "org.scalameta" %% "munit" % "1.2.3" % Test
    )
  )

lazy val visualizer = project.in(file("web-demo"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "cp-trie-visualizer",
    idePackagePrefix := Some("cp.visualizer"),
    Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala",
    libraryDependencies += "org.scala-lang.modules" % "scala-parser-combinators_sjs1_3" % "2.4.0",
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "web" / "scalajs",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "web" / "scalajs"
  )

import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.8.4"

lazy val root = project.in(file("."))
  .aggregate(languageServer, visualizer)
  .settings(
    name := "core-cp",
    idePackagePrefix := Some("cp"),
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
      "org.scalameta" %% "munit" % "1.2.3" % Test
    )
  )

// Compile the same CP sources for JavaScript. No browser or language-server policy lives in this project.
lazy val coreJS = project.in(file("core-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "core-cp-js",
    Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala",
    libraryDependencies += "org.scala-lang.modules" % "scala-parser-combinators_sjs1_3" % "2.4.0"
  )

lazy val languageServer = project.in(file("language-server"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(coreJS)
  .settings(
    name := "cp-language-server",
    idePackagePrefix := Some("cp.tooling"),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "scalajs"
  )

lazy val visualizer = project.in(file("web-demo"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(coreJS, languageServer)
  .settings(
    name := "cp-trie-visualizer",
    idePackagePrefix := Some("cp.visualizer"),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "web" / "scalajs" / "fast",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "web" / "scalajs" / "full"
  )

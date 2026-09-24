import org.scalajs.linker.interface.ModuleKind
import java.io.File

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

lazy val languageServerSources = Seq(
  idePackagePrefix := Some("cp.languageserver"),
  Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "shared" / "src" / "main" / "scala",
  Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "shared" / "src" / "test" / "scala",
  scalacOptions ++= Seq("-Wunused:all", "-Werror")
)

lazy val languageServer = project.in(file("language-server/js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(coreJS)
  .settings(languageServerSources)
  .settings(
    name := "cp-language-server-js",
    libraryDependencies ++= Seq(
      "com.lihaoyi" % "ujson_sjs1_3" % "4.4.3",
      "org.scalameta" % "munit_sjs1_3" % "1.2.3" % Test
    ),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value.getParentFile / "scalajs"
  )

// The root is the JVM compiler. Keeping this dependent project outside root's aggregation avoids
// a build dependency cycle; CI runs both language-server targets explicitly.
lazy val languageServerJVM = project.in(file("language-server/jvm"))
  .dependsOn(root)
  .settings(languageServerSources)
  .settings(
    name := "cp-language-server-jvm",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson" % "4.4.3",
      "org.scalameta" %% "munit" % "1.2.3" % Test
    ),
    Compile / mainClass := Some("cp.languageserver.jvm.Main"),
    Test / fork := true,
    Test / javaOptions += {
      val converter = fileConverter.value
      val classpath = (Runtime / fullClasspath).value.map(entry => converter.toPath(entry.data))
      "-Dcp.languageServer.classpath=" + classpath.mkString(File.pathSeparator)
    },
    assembly / mainClass := (Compile / mainClass).value,
    assembly / assemblyOutputPath := baseDirectory.value.getParentFile / "target" / "cp-language-server.jar"
  )

lazy val visualizer = project.in(file("web-demo"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(coreJS)
  .settings(
    name := "cp-trie-visualizer",
    idePackagePrefix := Some("cp.visualizer"),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "web" / "scalajs" / "fast",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "web" / "scalajs" / "full"
  )

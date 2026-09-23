scalaVersion := "3.8.4"

lazy val root = rootProject
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "playground-scalajs-api",
    scalacOptions ++= Seq("-Wunused:all", "-Werror"),
    libraryDependencies += "org.scalameta" % "munit_sjs1_3" % "1.2.3" % Test
  )

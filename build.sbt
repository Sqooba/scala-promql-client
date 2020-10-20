// *****************************************************************************
// Projects
// *****************************************************************************

lazy val root =
  project
    .in(file("."))
    .settings(settings)
    .settings(
      libraryDependencies ++= Seq(
        library.sttpCore,
        library.sttpCirce,
        library.sttpZioClient,
        library.circeCore,
        library.circeGeneric,
        library.circeParser,
        library.circeOptics,
        library.scalaLogging,
        library.typesafeConfig,
        library.zio        % Provided,
        library.zioTest % Test,
        library.zioTestSbt % Test,
        library.scalaTest % Test
      ),
      publishArtifact := true,
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
      scalaVersion := "2.12.12",
      crossScalaVersions := Seq("2.12.12", "2.13.3")
    )

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {

    object Version {
      val zio = "1.0.3"
      val sttp = "2.2.8"
      val circe = "0.13.0"
      val scalaTest = "3.0.8"
      val scalaLogging = "3.9.2"
      val typesafeConfig = "1.4.0"
    }

    val zio        = "dev.zio"       %% "zio"          % Version.zio
    val zioTest    = "dev.zio" %% "zio-test"     % Version.zio
    val zioTestSbt = "dev.zio" %% "zio-test-sbt" % Version.zio
    val sttpCore = "com.softwaremill.sttp.client" %% "core" % Version.sttp
    val sttpCirce = "com.softwaremill.sttp.client" %% "circe" % Version.sttp
    val sttpZioClient = "com.softwaremill.sttp.client" %% "async-http-client-backend-zio" % Version.sttp
    val circeCore = "io.circe" %% "circe-core" % Version.circe
    val circeGeneric = "io.circe" %% "circe-generic" % Version.circe
    val circeParser = "io.circe" %% "circe-parser" % Version.circe
    val circeOptics = "io.circe" %% "circe-optics" % Version.circe
    val scalaTest = "org.scalatest" %% "scalatest" % Version.scalaTest
    // Check if this is required for the lib?
    val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % Version.scalaLogging
    val typesafeConfig = "com.typesafe" % "config" % Version.typesafeConfig

  }

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val settings =
  commonSettings ++
    scalafmtSettings ++
    commandAliases

lazy val commonSettings =
  Seq(
    name := "scala-prom-client",
    organization := "io.sqooba.oss",
    organizationName := "Sqooba",
    homepage := Some(url("https://github.com/Sqooba/scala-promql-client")),
    licenses := Seq("Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/Sqooba/scala-promql-client.git"),
        "scm:git:git@github.com:Sqooba/scala-promql-client.git"
      )
    ),
    developers := List(
      Developer("shastick", "Shastick", "", url("https://github.com/Shastick"))
    ),
    scalacOptions --= Seq(
      "-Xlint:nullary-override"
    )
  )

lazy val scalafmtSettings =
  Seq(
    scalafmtOnCompile := true
  )

lazy val commandAliases =
  addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt") ++
    addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

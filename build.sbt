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
        library.zio            % Provided,
        library.zioTest        % Test,
        library.zioTestJunit   % Test,
        library.zioTestSbt     % Test,
        library.testContainers % Test
      ),
      publishArtifact := true,
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
      scalaVersion := "2.13.7",
      crossScalaVersions := Seq("2.12.15", "2.13.7")
    )

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {

    object Version {
      val zio                 = "1.0.12"
      val sttp                = "2.2.10"
      val circe               = "0.14.1"
      val scalaLogging        = "3.9.4"
      val typesafeConfig      = "1.4.1"
      val testContainersScala = "0.38.9"

    }

    val zio            = "dev.zio"                      %% "zio"                            % Version.zio
    val zioTest        = "dev.zio"                      %% "zio-test"                       % Version.zio
    val zioTestSbt     = "dev.zio"                      %% "zio-test-sbt"                   % Version.zio
    val zioTestJunit   = "dev.zio"                      %% "zio-test-junit"                 % Version.zio
    val sttpCore       = "com.softwaremill.sttp.client" %% "core"                           % Version.sttp
    val sttpCirce      = "com.softwaremill.sttp.client" %% "circe"                          % Version.sttp
    val sttpZioClient  = "com.softwaremill.sttp.client" %% "async-http-client-backend-zio"  % Version.sttp
    val circeCore      = "io.circe"                     %% "circe-core"                     % Version.circe
    val circeGeneric   = "io.circe"                     %% "circe-generic"                  % Version.circe
    val circeParser    = "io.circe"                     %% "circe-parser"                   % Version.circe
    val circeOptics    = "io.circe"                     %% "circe-optics"                   % Version.circe
    val scalaLogging   = "com.typesafe.scala-logging"   %% "scala-logging"                  % Version.scalaLogging
    val typesafeConfig = "com.typesafe"                  % "config"                         % Version.typesafeConfig
    val testContainers = "com.dimafeng"                 %% "testcontainers-scala-scalatest" % Version.testContainersScala

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
    name := "scala-promql-client",
    organization := "io.sqooba.oss",
    organizationName := "Sqooba",
    homepage := Some(url("https://github.com/Sqooba/scala-promql-client/")),
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
      Developer("shastick", "Shastick", "", url("https://github.com/Shastick")),
      Developer("ex0ns", "Ex0ns", "", url("https://github.com/ex0ns")),
      Developer("yannbolliger", "Yann Bolliger", "", url("https://github.com/yannbolliger"))
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

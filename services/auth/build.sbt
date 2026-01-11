ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.payments.platform"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := "auth-service",
    version := "1.0.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      guice,
      jdbc,
      evolutions,
      "org.postgresql" % "postgresql" % "42.7.1",
      "org.playframework.anorm" %% "anorm" % "2.7.0",
      "com.typesafe.play" %% "play-json" % "2.10.3",
      "com.nimbusds" % "oauth2-oidc-sdk" % "11.31.1",
      "com.github.jwt-scala" %% "jwt-core" % "9.4.5",
      "com.github.jwt-scala" %% "jwt-play-json" % "9.4.5",
      ws,
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test,
      "org.mockito" % "mockito-core" % "5.10.0" % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xlint"
    )
  )

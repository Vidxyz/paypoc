import sbt.util.Level

ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.payments.platform"
// Allow scala-xml version conflicts (sbt uses 2.12, plugins may use different versions)
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"
// Set eviction error level to warn instead of error for scala-xml conflicts
ThisBuild / evictionErrorLevel := Level.Warn

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, SwaggerPlugin)
  .settings(
    name := "fulfillment-service",
    version := "1.0.0-SNAPSHOT",
    swaggerDomainNameSpaces := Seq("models"),
    swaggerV3 := true,
    swaggerPrettyJson := true,
    swaggerTarget := baseDirectory.value / "public",
    swaggerFileName := "swagger.json",
    // Ensure swagger task runs before stage
    (stage in Universal) := ((stage in Universal) dependsOn swagger).value,
    libraryDependencies ++= Seq(
      guice,
      filters,
      "com.typesafe.play" %% "play-json" % "2.10.3",
      ws,
      "org.apache.kafka" % "kafka-clients" % "3.6.0",
      "org.webjars" % "swagger-ui" % "5.9.0",
      "com.auth0" % "java-jwt" % "4.4.0",
      "com.auth0" % "jwks-rsa" % "0.22.1",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.14.2",  // Explicitly add compatible version
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test,
      "org.mockito" % "mockito-core" % "5.10.0" % Test
    ),
    // Force jackson-databind to 2.14.2 to be compatible with jackson-module-scala 2.14.2
    // Auth0 libraries (java-jwt, jwks-rsa) pull in jackson-databind 2.15.0 which is incompatible
    // Force scala-xml to 2.1.0 to resolve version conflicts with sbt plugins
    // Force jackson-module-scala to 2.14.2 to override any older versions
    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.14.2",
      "com.fasterxml.jackson.core" % "jackson-core" % "2.14.2",
      "com.fasterxml.jackson.core" % "jackson-annotations" % "2.14.2",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.14.2",
      "org.scala-lang.modules" %% "scala-xml" % "2.1.0"
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xlint"
    )
  )

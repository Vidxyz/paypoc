ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.payments.platform"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, SwaggerPlugin)
  .settings(
    name := "cart-service",
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
      jdbc,
      evolutions,
      filters,
      "org.postgresql" % "postgresql" % "42.7.1",
      "org.playframework.anorm" %% "anorm" % "2.7.0",
      "com.typesafe.play" %% "play-json" % "2.10.3",
      ws,
      "org.apache.kafka" % "kafka-clients" % "3.6.0",
      "org.webjars" % "swagger-ui" % "5.9.0",
      "com.auth0" % "java-jwt" % "4.4.0",
      "com.auth0" % "jwks-rsa" % "0.22.1",
      "net.debasishg" %% "redisclient" % "3.42",  // Redis client for Scala
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test,
      "org.mockito" % "mockito-core" % "5.10.0" % Test
    ),
    // Force jackson-databind to 2.14.2 to be compatible with jackson-module-scala 2.14.3
    // Auth0 libraries (java-jwt, jwks-rsa) pull in jackson-databind 2.15.0 which is incompatible
    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.14.2",
      "com.fasterxml.jackson.core" % "jackson-core" % "2.14.2",
      "com.fasterxml.jackson.core" % "jackson-annotations" % "2.14.2"
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xlint"
    )
  )


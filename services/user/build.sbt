ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.payments.platform"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, SwaggerPlugin)
  .settings(
    name := "user-service",
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

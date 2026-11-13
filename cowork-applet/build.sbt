ThisBuild / scalaVersion := "3.10.1"
ThisBuild / organization := "com.cowork"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .enablePlugins(PlayService, PlayLayoutPlugin, SwaggerPlugin)
  .settings(
    name := "cowork-applet",
    PlayKeys.playDefaultPort := 8091,
    swaggerV3 := true,
    swaggerDomainNameSpaces := Seq("com.cowork.applet.domain"),
    libraryDependencies ++= Seq(
      guice,
      ws,
      "io.micrometer" % "micrometer-registry-prometheus" % "1.17.2",
      "net.logstash.logback" % "logstash-logback-encoder" % "9.0",
      "org.scalameta" %% "munit" % "1.3.6" % Test
    )
  )

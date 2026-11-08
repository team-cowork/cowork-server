ThisBuild / scalaVersion := "3.10.0"
ThisBuild / organization := "com.cowork"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .enablePlugins(PlayService, PlayLayoutPlugin)
  .settings(
    name := "cowork-applet",
    PlayKeys.playDefaultPort := 8091,
    libraryDependencies ++= Seq(
      guice,
      ws,
      "net.logstash.logback" % "logstash-logback-encoder" % "9.0",
      "org.scalameta" %% "munit" % "1.3.6" % Test
    )
  )

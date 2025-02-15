import Dependencies._

ThisBuild / scalaVersion := "2.13.15"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.example"
ThisBuild / organizationName := "example"
ThisBuild / homepage := Some(url("https://github.com/go4ble/mill-mqtt"))

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "Mill MQTT",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % "1.1.3",
      "org.apache.pekko" %% "pekko-stream" % "1.1.3",
      "com.softwaremill.sttp.client3" %% "pekko-http-backend" % "3.10.3",
      "com.softwaremill.sttp.client3" %% "play-json" % "3.10.3",
      "org.eclipse.paho" % "org.eclipse.paho.mqttv5.client" % "1.2.5",
      "ch.qos.logback" % "logback-classic" % "1.5.16"
    ),
    libraryDependencies += munit % Test,
    //
    buildInfoKeys := Seq[BuildInfoKey](name, version, homepage),
    buildInfoPackage := "millMqtt"
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.

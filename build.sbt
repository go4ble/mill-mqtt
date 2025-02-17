import Dependencies.*
import com.typesafe.sbt.packager.docker.ExecCmd

ThisBuild / scalaVersion := "2.13.15"
ThisBuild / version := "0.2.0"
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
      "ch.qos.logback" % "logback-classic" % "1.5.16",
      "com.auth0" % "java-jwt" % "4.5.0"
    ),
    libraryDependencies += munit % Test,
    //
    buildInfoKeys := Seq[BuildInfoKey](name, version, homepage),
    buildInfoPackage := "millMqtt",
    //
    dockerBaseImage := "eclipse-temurin:21-jre",
    dockerRepository := Some("ghcr.io/go4ble"),
    dockerCommands += ExecCmd("CMD", "-main", "millMqtt.App")
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.

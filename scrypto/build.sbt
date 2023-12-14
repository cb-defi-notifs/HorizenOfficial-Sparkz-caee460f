import sbt.Keys.{homepage, scalaVersion}

name := "scrypto"
description := "Cryptographic primitives for Scala"

javacOptions ++=
  "-source" :: "1.8" ::
    "-target" :: "1.8" ::
    Nil

libraryDependencies ++= Seq(
  "org.rudogma" %% "supertagged" % "1.5",
  "com.google.guava" % "guava" % "23.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "org.bouncycastle" % "bcprov-jdk18on" % "1.77"

)

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.1.+" % Test,
  "org.scalacheck" %% "scalacheck" % "1.14.+" % Test,
  "org.scalatestplus" %% "scalatestplus-scalacheck" % "3.1.0.0-RC2" % Test
)

wartremoverErrors := Seq()
import scala.util.Try

name := "sparkz-util"
description := "Common tools for sparkz projects"

javacOptions ++=
  "-source" :: "1.8" ::
    "-target" :: "1.8" ::
    Nil

libraryDependencies ++= Seq(
  "org.rudogma" %% "supertagged" % "1.5",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "org.scalatest" %% "scalatest" % "3.1.1" % Test,
  "org.scalacheck" %% "scalacheck" % "1.14.+" % Test,
  // https://mvnrepository.com/artifact/org.scalatestplus/scalatestplus-scalacheck
   "org.scalatestplus" %% "scalatestplus-scalacheck" % "3.1.0.0-RC2" % Test
)

Test / publishArtifact := true

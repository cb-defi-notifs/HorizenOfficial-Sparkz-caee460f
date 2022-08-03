name := "scorex-testkit"

libraryDependencies ++= Seq(
  "org.scalactic" %% "scalactic" % "3.2.12",
  "org.scalatest" %% "scalatest" % "3.2.12",
  "org.scalacheck" %% "scalacheck" % "1.16.0",
  "org.scalatestplus" %% "scalatestplus-scalacheck" % "3.1.0.0-RC2",
  "com.typesafe.akka" %% "akka-testkit" % "2.6.19"
)

fork in Test := true

javaOptions in Test ++= Seq("-Xmx2G")

parallelExecution in Test := false
name := "scorex-examples"

libraryDependencies ++= Seq(
  "org.scalactic" %% "scalactic" % "3.2.12" % "test",
  "org.scalatest" %% "scalatest" % "3.2.12" % "test",
  "org.scalacheck" %% "scalacheck" % "1.16.0" % "test",
  "org.scalatestplus" %% "scalatestplus-scalacheck" % "3.1.0.0-RC2" % Test,
  "org.scorexfoundation" %% "iodb" % "0.3.2",
  "com.typesafe.akka" %% "akka-testkit" % "2.6.19" % "test"
)

mainClass in assembly := Some("examples.hybrid.HybridApp")

assemblyJarName in assembly := "twinsChain.jar"

parallelExecution in Test := true

testForkedParallel in Test := true

test in assembly := {}

coverageExcludedPackages := "examples\\.hybrid\\.api\\.http.*"

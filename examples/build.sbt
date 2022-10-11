name := "sparkz-examples"

libraryDependencies ++= Seq(
  "org.scalactic" %% "scalactic" % "3.2.12" % "test",
  "org.scalatest" %% "scalatest" % "3.2.12" % "test",
  "org.scalacheck" %% "scalacheck" % "1.16.0" % "test",
  "org.scalatestplus" %% "scalatestplus-scalacheck" % "3.1.0.0-RC2" % Test,
  "com.typesafe.akka" %% "akka-testkit" % "2.6.19" % "test"
)

assembly / mainClass := Some("examples.hybrid.HybridApp")

assembly / assemblyJarName := "twinsChain.jar"

Test / parallelExecution := true

Test / testForkedParallel := true

assembly / test := {}

coverageExcludedPackages := "examples\\.hybrid\\.api\\.http.*"

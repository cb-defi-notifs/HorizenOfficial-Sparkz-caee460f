name := "sparkz-examples"

libraryDependencies ++= Seq(
  "org.scalatestplus" %% "scalatestplus-scalacheck" % "3.1.0.0-RC2" % Test,
  "com.typesafe.akka" %% "akka-testkit" % "2.6.19" % "test",
  "com.google.guava" % "guava" % "23.0",
  "net.jpountz.lz4" % "lz4" % "1.3.0",
  "org.slf4j" % "slf4j-api" % "2.0.4",
  "org.scalactic" %% "scalactic" % "3.2.14" % "test",
  "org.scalacheck" %% "scalacheck" % "1.17.0" % "test",
  "com.novocode" % "junit-interface" % "0.11" % "test",
  "org.rocksdb" % "rocksdbjni" % "7.6.0" % "test",
  "org.iq80.leveldb" % "leveldb" % "0.12" % "test"
)

assembly / mainClass := Some("examples.hybrid.HybridApp")

assembly / assemblyJarName := "twinsChain.jar"

Test / parallelExecution := true

Test / testForkedParallel := true

assembly / test := {}

coverageExcludedPackages := "examples\\.hybrid\\.api\\.http.*"

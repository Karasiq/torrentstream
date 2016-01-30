lazy val rootSettings = Seq(
  name := "torrentstream",
  isSnapshot := true,
  version := "1.0.0-SNAPSHOT",
  scalaVersion := "2.11.7",
  resolvers += MavenRepository("JBoss Thirdparty Releases", "https://repository.jboss.org/nexus/content/repositories/thirdparty-releases"),
  libraryDependencies ++= {
    val akkaV = "2.4.1"
    Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "com.github.karasiq" %% "commons" % "1.0.3",
      "com.github.karasiq" %% "commons-akka" % "1.0.3",
      "com.typesafe.akka" %% "akka-actor" % akkaV,
      "net.codingwell" %% "scala-guice" % "4.0.1",
      "org.scalatest" %% "scalatest" % "2.2.4" % "test",
      "com.typesafe.akka" %% "akka-stream-experimental" % "2.0.3",
      "com.typesafe.akka" %% "akka-http-experimental" % "2.0.3",
      "com.turn" % "ttorrent" % "1.4"
    )
  },
  mainClass in Compile := Some("com.karasiq.torrentstream.app.Main")
)

lazy val root = Project("torrentstream", file("."))
  .settings(rootSettings)
  .enablePlugins(JavaAppPackaging)
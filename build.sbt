lazy val commonSettings = Seq(
  organization := "com.github.karasiq",
  isSnapshot := false,
  version := "1.0.1",
  scalaVersion := "2.11.7"
)

lazy val librarySettings = Seq(
  name := "bittorrent",
  libraryDependencies ++= {
    val akkaV = "2.4.1"
    Seq(
      "com.typesafe.akka" %% "akka-actor" % akkaV,
      "org.scalatest" %% "scalatest" % "2.2.4" % "test",
      "com.typesafe.akka" %% "akka-stream-experimental" % "2.0.3",
      "com.typesafe.akka" %% "akka-http-experimental" % "2.0.3",
      "commons-codec" % "commons-codec" % "1.8",
      "commons-io" % "commons-io" % "2.4",
      "org.parboiled" %% "parboiled" % "2.1.1",
      "org.bouncycastle" % "bcprov-jdk15on" % "1.52",
      "org.bouncycastle" % "bcpkix-jdk15on" % "1.52"
    )
  },
  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  publishArtifact in Test := false,
  pomIncludeRepository := { _ ⇒ false },
  licenses := Seq("Apache License, Version 2.0" → url("http://opensource.org/licenses/Apache-2.0")),
  homepage := Some(url("https://github.com/Karasiq/torrentstream")),
  pomExtra := <scm>
    <url>git@github.com:Karasiq/torrentstream.git</url>
    <connection>scm:git:git@github.com:Karasiq/torrentstream.git</connection>
  </scm>
    <developers>
      <developer>
        <id>karasiq</id>
        <name>Piston Karasiq</name>
        <url>https://github.com/Karasiq</url>
      </developer>
    </developers>
)

import com.karasiq.scalajsbundler.dsl.{Script, _}
lazy val backendSettings = Seq(
  name := "torrentstream",
  libraryDependencies ++= Seq(
    "com.github.karasiq" %% "mapdbutils" % "1.1.1",
    "org.mapdb" % "mapdb" % "2.0-beta12",
    "me.chrons" %% "boopickle" % "1.1.2"
  ),
  mainClass in Compile := Some("com.karasiq.torrentstream.app.Main"),
  scalaJsBundlerCompile in Compile <<= (scalaJsBundlerCompile in Compile).dependsOn(fullOptJS in Compile in frontend),
  scalaJsBundlerAssets in Compile += {
    Bundle("index",
      // Static
      Html from TestPageAssets.index,
      Style from TestPageAssets.style,

      // jQuery
      Script from url("https://code.jquery.com/jquery-1.12.0.js"),

      // Bootstrap
      Style from url("https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/css/bootstrap.css"),
      Script from url("https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/js/bootstrap.js"),
      Static("fonts/glyphicons-halflings-regular.eot") from url("https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/fonts/glyphicons-halflings-regular.eot"),
      Static("fonts/glyphicons-halflings-regular.svg") from url("https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/fonts/glyphicons-halflings-regular.svg"),
      Static("fonts/glyphicons-halflings-regular.ttf") from url("https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/fonts/glyphicons-halflings-regular.ttf"),
      Static("fonts/glyphicons-halflings-regular.woff") from url("https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/fonts/glyphicons-halflings-regular.woff"),
      Static("fonts/glyphicons-halflings-regular.woff2") from url("https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/fonts/glyphicons-halflings-regular.woff2"),

      // Scala.js app
      Script from file("frontend") / "target" / "scala-2.11" / "torrentstream-frontend-opt.js",
      Script from file("frontend") / "target" / "scala-2.11" / "torrentstream-frontend-launcher.js"
    )
  }
)

lazy val frontendSettings = Seq(
  persistLauncher in Compile := true,
  name := "torrentstream-frontend",
  libraryDependencies ++= Seq(
    "com.github.karasiq" %%% "scalajs-bootstrap" % "1.0.2",
    "me.chrons" %%% "boopickle" % "1.1.2"
  )
)

lazy val library = Project("bittorrent", file("library"))
  .settings(commonSettings, librarySettings)

lazy val backend = Project("torrentstream", file("."))
  .dependsOn(library)
  .settings(commonSettings, backendSettings)
  .enablePlugins(ScalaJSBundlerPlugin, JavaAppPackaging)

lazy val frontend = Project("torrentstream-frontend", file("frontend"))
  .settings(commonSettings, frontendSettings)
  .enablePlugins(ScalaJSPlugin)
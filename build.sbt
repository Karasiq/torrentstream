lazy val commonSettings = Seq(
  organization := "com.github.karasiq",
  isSnapshot := false,
  version := "1.0.3",
  scalaVersion := "2.11.11"
)

lazy val publishSettings = Seq(
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

lazy val librarySettings = Seq(
  name := "bittorrent",
  crossScalaVersions := Seq("2.11.11", "2.12.3"),
  libraryDependencies ++= {
    val akkaV = "2.5.4"
    val akkaHttpV = "10.0.10"
    Seq(
      "com.typesafe.akka" %% "akka-actor" % akkaV,
      "com.typesafe.akka" %% "akka-stream" % akkaV,
      "com.typesafe.akka" %% "akka-http" % akkaHttpV,
      "commons-codec" % "commons-codec" % "1.10",
      "commons-io" % "commons-io" % "2.5",
      "org.parboiled" %% "parboiled" % "2.1.4",
      "org.bouncycastle" % "bcprov-jdk15on" % "1.58",
      "org.scalatest" %% "scalatest" % "3.0.4" % "test"
    )
  }
)

lazy val backendSettings = Seq(
  name := "torrentstream",
  libraryDependencies ++= Seq(
    "com.github.karasiq" %% "mapdbutils" % "1.1.1",
    "org.mapdb" % "mapdb" % "2.0-beta13",
    "me.chrons" %% "boopickle" % "1.2.5"
  ),
  mainClass in Compile := Some("com.karasiq.torrentstream.app.Main"),
  scalaJsBundlerCompile in Compile <<= (scalaJsBundlerCompile in Compile).dependsOn(fullOptJS in Compile in frontend),
  scalaJsBundlerAssets in Compile += {
    import com.karasiq.scalajsbundler.ScalaJSBundler.PageContent
    import com.karasiq.scalajsbundler.dsl._

    def fontPackage(name: String, baseUrl: String): Seq[PageContent] = {
      Seq("eot", "svg", "ttf", "woff", "woff2").map { ext ⇒
        Static(s"fonts/$name.$ext") from url(s"$baseUrl.$ext")
      }
    }

    val jsDeps = Seq(
      // jQuery
      Script from url("https://code.jquery.com/jquery-1.12.0.js"),

      // Bootstrap
      Style from url("https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/css/bootstrap.css"),
      Script from url("https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/js/bootstrap.js"),

      // Font Awesome
      Style from url("https://raw.githubusercontent.com/FortAwesome/Font-Awesome/v4.5.0/css/font-awesome.css")
    )

    val appStatic = Seq(
      // Static
      Html from TorrentStreamAssets.index,
      Style from TorrentStreamAssets.style
    )

    val fonts = fontPackage("glyphicons-halflings-regular", "https://raw.githubusercontent.com/twbs/bootstrap/v3.3.6/dist/fonts/glyphicons-halflings-regular") ++
      fontPackage("fontawesome-webfont", "https://raw.githubusercontent.com/FortAwesome/Font-Awesome/v4.5.0/fonts/fontawesome-webfont")

    Bundle("index", jsDeps, appStatic, fonts, scalaJsApplication(frontend).value)
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
  .settings(commonSettings, librarySettings, publishSettings)

lazy val backend = Project("torrentstream", file("."))
  .dependsOn(library)
  .settings(commonSettings, backendSettings)
  .enablePlugins(ScalaJSBundlerPlugin, JavaAppPackaging)

lazy val frontend = Project("torrentstream-frontend", file("frontend"))
  .settings(commonSettings, frontendSettings)
  .enablePlugins(ScalaJSPlugin)
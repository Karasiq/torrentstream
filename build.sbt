lazy val commonSettings = Seq(
  organization := "com.github.karasiq",
  isSnapshot := true,
  version := "1.0.0-SNAPSHOT",
  scalaVersion := "2.11.7"
)

import com.karasiq.scalajsbundler.compilers.{AssetCompilers, ConcatCompiler}
import com.karasiq.scalajsbundler.dsl.{Script, _}
lazy val backendSettings = Seq(
  name := "torrentstream",
  libraryDependencies ++= {
    val akkaV = "2.4.1"
    Seq(
      "com.github.karasiq" %% "commons" % "1.0.3",
      "com.github.karasiq" %% "commons-akka" % "1.0.3",
      "com.typesafe.akka" %% "akka-actor" % akkaV,
      "net.codingwell" %% "scala-guice" % "4.0.1",
      "org.scalatest" %% "scalatest" % "2.2.4" % "test",
      "com.typesafe.akka" %% "akka-stream-experimental" % "2.0.3",
      "com.typesafe.akka" %% "akka-http-experimental" % "2.0.3",
      "commons-codec" % "commons-codec" % "1.8",
      "commons-io" % "commons-io" % "2.4",
      "org.simpleframework" % "simple" % "4.1.21",
      "org.slf4j" % "slf4j-log4j12" % "1.6.4",
      "com.github.karasiq" %% "mapdbutils" % "1.1.1",
      "org.mapdb" % "mapdb" % "2.0-beta12",
      "me.chrons" %% "boopickle" % "1.1.2",
      "org.parboiled" %% "parboiled" % "2.1.1"
    )
  },
  mainClass in Compile := Some("com.karasiq.torrentstream.app.Main"),
  scalaJsBundlerCompilers in Compile := AssetCompilers {
    case Mimes.javascript ⇒
      ConcatCompiler
  }.<<=(AssetCompilers.default),
  scalaJsBundlerCompile in Compile <<= (scalaJsBundlerCompile in Compile).dependsOn(fastOptJS in Compile in frontend),
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
      Script from file("frontend") / "target" / "scala-2.11" / "torrentstream-frontend-fastopt.js",
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

lazy val backend = Project("torrentstream", file("."))
  .settings(commonSettings, backendSettings)
  .enablePlugins(ScalaJSBundlerPlugin, JavaAppPackaging)

lazy val frontend = Project("torrentstream-frontend", file("frontend"))
  .settings(commonSettings, frontendSettings)
  .enablePlugins(ScalaJSPlugin)
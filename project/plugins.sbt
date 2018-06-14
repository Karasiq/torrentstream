logLevel := Level.Warn

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.5")

addSbtPlugin("com.github.karasiq" % "sbt-scalajs-bundler" % "1.2.1")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.20")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")

addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.7")

addSbtPlugin("ohnosequences" % "sbt-github-release" % "0.7.0")

libraryDependencies += "com.lihaoyi" %% "scalatags" % "0.6.2"

resolvers += "repo.jenkins-ci.org" at "http://repo.jenkins-ci.org/public" // org.jenkins-ci#annotation-indexer;1.4: not found
# TorrentStream [![Build Status](https://travis-ci.org/Karasiq/torrentstream.svg?branch=master)](https://travis-ci.org/Karasiq/torrentstream)
TorrentStream is a torrent streaming engine, written in [Scala](http://scala-lang.org/) and based on [akka-streams](http://doc.akka.io/docs/akka-stream-and-http-experimental/current/scala/stream-index.html).

# How to use
* As an application
  * Start server with `sbt run`, or use [precompiled package](https://github.com/Karasiq/torrentstream/releases/download/v1.0.1/torrentstream-1.0.1.zip)
  * Open http://127.0.0.1:8901 in browser
* As a library
  * `libraryDependencies += "com.github.karasiq" %% "bittorrent" % "1.0.1"`

# License
Copyright 2016 Karasiq

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

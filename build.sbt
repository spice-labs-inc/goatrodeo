val scala3Version = "3.3.1"
val luceneVersion = "4.3.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "goatrodeo",
    version := "0.3.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies += "org.scala-lang" %% "toolkit" % "0.1.7",
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.2.0",
    libraryDependencies += "org.apache.bcel" % "bcel" % "6.8.1",
    libraryDependencies += "com.github.scopt" %% "scopt" % "4.1.0",
    libraryDependencies += "org.apache.lucene" % "lucene-core" % luceneVersion,
    libraryDependencies += "org.apache.lucene" % "lucene-analyzers-common" % luceneVersion,
    libraryDependencies += "org.apache.lucene" % "lucene-queryparser" % luceneVersion,
    libraryDependencies += "org.xerial" % "sqlite-jdbc" % "3.45.0.0",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test,
    // https://mvnrepository.com/artifact/commons-io/commons-io
    libraryDependencies += "commons-io" % "commons-io" % "2.15.1",
    libraryDependencies += "com.lihaoyi" %% "upickle" % "3.2.0",
    assembly / mainClass := Some("goatrodeo.Howdy")
  )

ThisBuild / assemblyJarName := "goatrodeo.jar"

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case _                                   => MergeStrategy.last
}

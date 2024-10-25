val projectName = "goatrodeo"
val projectVersion = "0.5.0-SNAPSHOT"
val scala3Version = "3.3.3"
val luceneVersion = "4.3.0"

fork := true

resolvers += "OW2" at "https://repository.ow2.org/nexus/content/repositories/public/"
Test / logBuffered := false

lazy val root = project
  .in(file("."))
  .settings(
    name := projectName,
    version := projectVersion,
    scalaVersion := scala3Version,
    libraryDependencies += "org.scala-lang" %% "toolkit" % "0.4.0",
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
    libraryDependencies += "org.apache.bcel" % "bcel" % "6.10.0",
    libraryDependencies += "com.github.scopt" %% "scopt" % "4.1.0",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test,
    libraryDependencies += "commons-io" % "commons-io" % "2.16.1",
    libraryDependencies += "io.bullet" %% "borer-core" % "1.14.1",
    libraryDependencies += "io.bullet" %% "borer-derivation" % "1.14.1",

    // json4s
    libraryDependencies += "org.json4s" %% "json4s-native" % "4.0.7",
    libraryDependencies += "com.github.luben" % "zstd-jni" % "1.5.6-4",
    // https://mvnrepository.com/artifact/org.apache.commons/commons-compress
    libraryDependencies += "org.apache.commons" % "commons-compress" % "1.26.1",

    // https://mvnrepository.com/artifact/com.jguild.jrpm/jrpm
    // libraryDependencies += "com.jguild.jrpm" % "jrpm" % "0.9",
    assembly / mainClass := Some("io.spicelabs.goatrodeo.Howdy"),
    compileOrder := CompileOrder.JavaThenScala
  )

ThisBuild / assemblyJarName := "goatrodeo.jar"

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case _                                   => MergeStrategy.last
}

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

Universal / mappings += file("data/grim.json") -> "data/grim.json"
Docker / packageName := projectName
Docker / version := projectVersion
Docker / maintainer := "ext-engineering@spicelabs.io"

dockerBaseImage := "eclipse-temurin:21-jre-ubi9-minimal" 
dockerLabels := Map.empty
dockerExposedPorts := Seq.empty

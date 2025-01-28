import java.nio.file.{FileAlreadyExistsException, Files, Paths}
import scala.sys.process._

val projectName = "goatrodeo"
val projectVersion = "0.6.2-SNAPSHOT"
val scala3Version = "3.6.3"

fork := true

resolvers += "OW2" at "https://repository.ow2.org/nexus/content/repositories/public/"
Test / logBuffered := false

lazy val root = project
  .in(file("."))
  .settings(
    name := projectName,
    scalaVersion := scala3Version,
    libraryDependencies += "org.scala-lang" %% "toolkit" % "0.4.0",
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
    libraryDependencies += "org.apache.bcel" % "bcel" % "6.10.0",
    libraryDependencies += "com.github.scopt" %% "scopt" % "4.1.0",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test,
    libraryDependencies += "commons-io" % "commons-io" % "2.16.1",
    libraryDependencies += "io.bullet" %% "borer-core" % "1.14.1",
    libraryDependencies += "io.bullet" %% "borer-derivation" % "1.14.1",
    libraryDependencies += "com.palantir.isofilereader" % "isofilereader" % "0.6.1",

    // json4s
    libraryDependencies += "org.json4s" %% "json4s-native" % "4.0.7",
    libraryDependencies += "com.github.luben" % "zstd-jni" % "1.5.6-4",
    libraryDependencies += "org.apache.commons" % "commons-compress" % "1.26.1",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.15",
    // libraryDependencies += "slf4j" % "simple" % "2.0.16",
    libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    libraryDependencies += "org.apache.tika" % "tika-core" % "3.0.0",
    assembly / mainClass := Some("goatrodeo.Howdy"),
    compileOrder := CompileOrder.JavaThenScala
  )

ThisBuild / assemblyJarName := "goatrodeo.jar"

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case _                                   => MergeStrategy.last
}

// Fetch test data from r2 before running tests
Test / testOptions += Tests.Setup(() => {
  val log = (streams.value: @sbtUnchecked).log
  log.info("Downloading and caching test data…")
  try {
    log.info("\t* Creating test_data/iso_tests if it doesn't already exist…")
    Files.createDirectory(Paths.get("test_data/iso_tests"))
  } catch {
    case fE: FileAlreadyExistsException =>
      log.info("\t! iso_tests directory already exists.")
    case e: Throwable =>
      val err = s"Exception setting up iso_tests directory: ${e.getMessage}"
      log.error(err)
      throw new MessageOnlyException(err)
  }

  try {
    log.info("\t* Creating test_data/gem_tests if it doesn't already exist…")
    Files.createDirectory(Paths.get("test_data/gem_tests"))
  } catch {
    case fE: FileAlreadyExistsException =>
      log.info("\t! gem_tests directory already exists.")
    case e: Throwable =>
      val err = s"Exception setting up gem_tests directory: ${e.getMessage}"
      log.error(err)
      throw new MessageOnlyException(err)
  }

  try {
    log.info("\t* Fetching test ISOs…")
    url("https://public-test-data.spice-labs.dev/iso_of_archives.iso") #> file(
      "./test_data/iso_tests/iso_of_archives.iso"
    ) ! log
    url("https://public-test-data.spice-labs.dev/simple.iso") #> file(
      "./test_data/iso_tests/simple.iso"
    ) ! log
    log.info("\t * Fetching test Gems…")
    url(
      "https://public-test-data.spice-labs.dev/java-properties-0.3.0.gem"
    ) #> file("./test_data/gem_tests/java-properties-0.3.0.gem") ! log
  } catch {
    case e: Throwable =>
      val err = s"Exception fetching iso test files: ${e.getMessage}"
      log.error(err)
      throw new MessageOnlyException(err)
  }
  log.info("Test data caching complete.")
})

// Verify that git LFS is installed and files are correct before running tests
Test / testOptions += Tests.Setup(() => {
  val log = (streams.value: @sbtUnchecked).log
  log.info("Testing for git LFS…")
  if ("git lfs status".! == 0) {
    log.info("git lfs found, proceeding…")
  } else {
    val err =
      "git lfs not found. Please review the README.md for setup instructions!"
    log.error(err)
    throw new MessageOnlyException(err)
  }
  {
    log.info("Running a `git lfs pull`…")
    if ("git lfs pull".! == 0) {
      log.info("git lfs files should all be synced now.")
    } else {
      val err = "`git lfs pull` failed!"
      log.error(err)
      throw new MessageOnlyException(err)
    }
  } 
})

enablePlugins(JavaAppPackaging)
enablePlugins(GitVersioningPlugin)
enablePlugins(DockerPlugin)

Universal / mappings += file("data/grim.json") -> "data/grim.json"
Docker / packageName := projectName
Docker / maintainer := "ext-engineering@spicelabs.io"

dockerBaseImage := "eclipse-temurin:21-jre-ubi9-minimal"
dockerLabels := Map.empty
dockerExposedPorts := Seq.empty

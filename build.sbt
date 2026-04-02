import java.nio.file.{FileAlreadyExistsException, Files, Paths}
import scala.sys.process._

val projectName = "goatrodeo"
val scala3Version = "3.8.3"

ThisBuild / organization := "io.spicelabs"
ThisBuild / version := "0.0.1-SNAPSHOT" // Don't change this, it is overridden by the GitHub Actions workflow
ThisBuild / licenses := Seq(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")
)
ThisBuild / homepage := Some(url("https://github.com/spice-labs-inc/goatrodeo"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/spice-labs-inc/goatrodeo"),
    "scm:git@github.com:spice-labs-inc/goatrodeo.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "spicelabs",
    name = "Spice Labs",
    email = "engineering@spicelabs.io",
    url = url("https://github.com/spice-labs-inc")
  )
)

ThisBuild / publishTo := {
  val repo = "https://maven.pkg.github.com/spice-labs-inc/goatrodeo"
  Some("GitHub Package Registry" at repo)
}
credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "x-access-token",
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

// ThisBuild / publishTo := sonatypePublishToBundle.value
// ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
// ThisBuild / sonatypeProfileName := "io.spicelabs"

// GPG signing
ThisBuild / pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray)
Global / excludeLintKeys += pgpPassphrase

// Publish both standard and fat jars
Compile / packageBin := (Compile / packageBin).value
val fatJar = taskKey[File]("Assembles the fat jar for publishing")

fatJar := {
  val jar = (Compile / assembly).value
  val targetPath = target.value / s"${projectName}-${version.value}-fat.jar"
  IO.copyFile(jar, targetPath)
  targetPath
}

// Tasks to verify fat JAR integrity
val verifyJarContents = taskKey[Unit]("Verify no signature files in fat JAR")
val testFatJar = taskKey[Unit]("Test that fat JAR runs without signature errors")

verifyJarContents := {
  val fatJarFile = (Compile / assembly).value
  val jar = new java.util.jar.JarFile(fatJarFile)
  try {
    import scala.jdk.CollectionConverters._
    val entries = jar.entries().asScala.toList
    val badEntries = entries.filter { e =>
      val name = e.getName
      name.startsWith("META-INF/") && (
        name.endsWith(".SF") || name.endsWith(".DSA") ||
        name.endsWith(".RSA") || name.endsWith(".EC") || name.startsWith("SIG-")
      )
    }
    if (badEntries.nonEmpty) {
      throw new MessageOnlyException(
        s"Found signature files in fat JAR: ${badEntries.map(_.getName).mkString(", ")}"
      )
    }
  } finally {
    jar.close()
  }
}

testFatJar := {
  val fatJarFile = (Compile / assembly).value
  val result = scala.sys.process.Process(Seq("java", "-jar", fatJarFile.getAbsolutePath, "--help")).!
  if (result != 0) {
    throw new MessageOnlyException(s"Fat JAR failed to execute with exit code: $result")
  }
}

// Hook fat JAR tests into `sbt test`
Test / test := (Test / test).dependsOn(verifyJarContents, testFatJar).value

publishMavenStyle := true
publish / packagedArtifacts += (Artifact(
  projectName,
  "jar",
  "jar",
  classifier = "fat"
) -> fatJar.value)

// If "TEST_THREAD_CNT" is set that means we're
// running on a memory constrained system and we
// don't want to fork a process to run tests
if (System.getenv("TEST_THREAD_CNT") == null) {
  fork := true
} else {
  // By default, if `TEST_THREAD_CNT` is set, sbt
  // will *not* fork a new Java process because
  // GitHub runners have very little memory, however
  // on 16MB local dev machines, forking is required
  // because testing, etc. are not "one and done"
  // so set `TEST_FORK` to true and the
  // tests will be forked
  fork := Option(System.getenv("TEST_FORK")).isDefined

}

ThisBuild / scalacOptions ++=
  Seq(
    "-deprecation",
    "-unchecked",
    "-Wunused:imports",
    "-feature",
    "-release",
    "17",
    "-Yexplicit-nulls"
  )

// Add GitHub Packages resolver
resolvers += "GitHub Package Registry" at "https://maven.pkg.github.com/spice-labs-inc/goatrodeo"

resolvers += "OW2" at "https://repository.ow2.org/nexus/content/repositories/public/"

// pick up local Maven generated artifacts
resolvers += Resolver.mavenLocal

Test / logBuffered := false

lazy val root = project
  .in(file("."))
  .enablePlugins(
    BuildInfoPlugin,
    JavaAppPackaging,
    GitVersioningPlugin,
    AssemblyPlugin
  )
  .settings(
    name := projectName,
    scalaVersion := scala3Version,
    semanticdbEnabled := true, // enable SemanticDB,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
    libraryDependencies += "org.apache.bcel" % "bcel" % "6.11.0",
    libraryDependencies += "com.github.scopt" %% "scopt" % "4.1.0",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test,
    libraryDependencies += "org.scalameta" %% "munit-scalacheck" % "0.7.29" % Test,
    libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.18.1" % Test,
    libraryDependencies += "commons-io" % "commons-io" % "2.18.0",
    libraryDependencies += "io.bullet" %% "borer-derivation" % "1.14.1",
    libraryDependencies += "com.palantir.isofilereader" % "isofilereader" % "0.6.1",
    libraryDependencies += "org.json4s" %% "json4s-native" % "4.0.7",
    libraryDependencies += "org.apache.commons" % "commons-compress" % "1.28.0",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.15",
    libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0",
    libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    libraryDependencies += "org.apache.tika" % "tika-core" % "3.2.3",
    libraryDependencies += "com.github.package-url" % "packageurl-java" % "1.5.0",
    libraryDependencies += "io.spicelabs" %% "cilantro" % "0.1.17",
    libraryDependencies += "com.github.dwickern" %% "scala-nameof" % "5.0.0" % "provided",

    // Spice Labs "readers"
    libraryDependencies += "io.spicelabs" % "baharat" % "0.0.4",
    libraryDependencies += "io.spicelabs" % "annatto" % "0.1.0",
    libraryDependencies += "io.spicelabs" % "saffron" % "0.2.3",

    assembly / mainClass := Some("io.spicelabs.goatrodeo.Howdy"),
    assembly / assemblyJarName := s"${projectName}-${version.value}-fat.jar",
    compileOrder := CompileOrder.JavaThenScala,
    libraryDependencySchemes += "com.github.luben" % "zstd-jni" % VersionScheme.Always,
    scalacOptions += "-no-indent",
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      BuildInfoKey.action("commit") {
        scala.sys.process.Process("git rev-parse HEAD").!!.trim
      }
    ),
    buildInfoPackage := "hellogoat",
    // Don't bundle logback.xml in the library jar — consumers provide their own
    Compile / packageBin / mappings ~= { _.filter(_._2 != "logback.xml") }
  )

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  // Discard signature files from signed JARs
  case PathList("META-INF", name) if name.endsWith(".SF") || name.endsWith(".DSA") ||
    name.endsWith(".RSA") || name.endsWith(".EC") || name.startsWith("SIG-") =>
    MergeStrategy.discard
  case _ => MergeStrategy.last
}

Test / testOptions += Tests.Setup(() => {
  val log = (streams.value: @sbtUnchecked).log
  log.info("Downloading and caching test data…")

  try {
    val toDownload: Seq[(String, String, Option[String])] = Vector(
      ("docker_tests", "bigtent_2025_03_22_docker.tar", None),
      ("docker_tests", "grinder_bt_pg_docker.tar", None),
      ("iso_tests", "iso_of_archives.iso", None),
      ("iso_tests", "simple.iso", None),
      ("", "sample-tomcat-6.war", None),
      ("", "EnterpriseHelloWorld.ear", None),
      ("apk_tests", "bitbar-sample-app.apk", None),
      ("gem_tests", "java-properties-0.3.0.gem", None),
      ("deb_tests", "hello_2.10-3_arm64.deb", None),
      (
        "adg_tests",
        "repo_ea.tgz",
        Some(
          "tar -xzvf test_data/download/adg_tests/repo_ea.tgz -C test_data/download/adg_tests/"
        )
      )
    )

    for {
      (dir, item, cmd) <- toDownload
    } {
      val f = file(f"./test_data/download/${dir}/${item}")
      f.getParentFile().mkdirs()
      if (!f.exists()) {
        log.info(f"Downloading ${item}")
        var loopCnt = 0
        var keepOn = true

        while (keepOn) {
          val cmdResult =
            url(f"https://public-test-data.spice-labs.dev/${item}") #> f ! log

          if (cmdResult == 0) {
            keepOn = false
          } else {
            loopCnt += 1
            if (loopCnt >= 10) {
              throw new Exception(
                f"Failed to download ${item} after ${loopCnt} tries. Aborting"
              )
            }
          }
        }
        cmd match {
          case None      =>
          case Some(cmd) => cmd ! log
        }
      }
    }

  } catch {
    case e: Exception =>
      val err = s"Exception fetching test files: ${e.getMessage}"
      log.error(err)
      println(err)
      throw new MessageOnlyException(err)
  }
  log.info("Test data caching complete.")
})

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

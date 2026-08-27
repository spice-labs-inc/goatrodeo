import java.nio.file.{FileAlreadyExistsException, Files, Paths}
import scala.sys.process._

val projectName = "goatrodeo"
val scala3Version = "3.8.3"

ThisBuild / organization := "io.spicelabs"

// Derive `version` from git. Format follows what sbt-git-versioning used to
// produce, since `BuildInfo.version` is consumed at runtime:
//   - HEAD is on a release tag       → "0.15.5"            (or "0.15.5-dirty-SNAPSHOT")
//   - HEAD is N commits past a tag   → "0.15.5-3-abc1234-SNAPSHOT" (+"-dirty" if dirty)
//   - No tags / not in a git repo    → "0.0.0-SNAPSHOT"
// Release builds in CI override this entirely via `sbt "set ThisBuild / version := ..."`.
ThisBuild / version := {
  val SemVer = """^v?(\d+)\.(\d+)\.(\d+)$""".r
  def run(cmd: String): Vector[String] =
    scala.util.Try(Process(cmd).lineStream.toVector).getOrElse(Vector.empty)

  val dirty = run("git status --porcelain").nonEmpty
  val headTags = run("git tag --points-at HEAD").collect {
    case t @ SemVer(ma, mi, pa) => (t, ma.toInt, mi.toInt, pa.toInt)
  }
  if (headTags.nonEmpty) {
    // HEAD is tagged. When multiple semver tags decorate it, pick the largest.
    val chosen = headTags
      .maxBy { case (_, ma, mi, pa) => (ma, mi, pa) }
      ._1
      .stripPrefix("v")
    if (dirty) s"$chosen-dirty-SNAPSHOT" else chosen
  } else {
    val baseTag =
      run("git describe --tags --abbrev=0 --match=v[0-9]*").headOption
        .orElse(run("git describe --tags --abbrev=0").headOption)
        .getOrElse("v0.0.0")
    val base = baseTag.stripPrefix("v")
    val sha = run("git rev-parse --short HEAD").headOption.getOrElse("unknown")
    val commits =
      run(s"git rev-list --count $baseTag..HEAD").headOption.getOrElse("0")
    val dirtyTag = if (dirty) "-dirty" else ""
    s"$base-$commits-$sha$dirtyTag-SNAPSHOT"
  }
}

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
val testFatJar =
  taskKey[Unit]("Test that fat JAR runs without signature errors")

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
          name.endsWith(".RSA") || name.endsWith(".EC") || name.startsWith(
            "SIG-"
          )
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
  val result = scala.sys.process
    .Process(Seq("java", "-jar", fatJarFile.getAbsolutePath, "--help"))
    .!
  if (result != 0) {
    throw new MessageOnlyException(
      s"Fat JAR failed to execute with exit code: $result"
    )
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

// Run independent test classes in parallel only when not in CI.
// CI is detected by TEST_THREAD_CNT being set in build_test.yml.
Test / testForkedParallel := System.getenv("TEST_THREAD_CNT") == null

// Align the forked test JVM with the machine it runs on: 50% of physical RAM,
// floored at 1G and capped at 32G. Large machines get a heap that supports
// many in-flight workers; small machines get a proportionally small heap.
def machineRamBytes: Long = {
  val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean
  bean match {
    case c: com.sun.management.OperatingSystemMXBean => c.getTotalMemorySize()
    case _                                           => 4L * 1024 * 1024 * 1024
  }
}

Test / javaOptions ++= Seq(
  {
    val half = machineRamBytes / 2
    val heap =
      math.min(32L * 1024 * 1024 * 1024, math.max(1024L * 1024 * 1024, half))
    s"-Xmx${heap}"
  }
)

ThisBuild / scalacOptions ++=
  Seq(
    "-deprecation",
    "-unchecked",
    "-Wunused:imports",
    "-feature",
    "-release",
    "21",
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
    AssemblyPlugin
  )
  .settings(
    name := projectName,
    scalaVersion := scala3Version,
    semanticdbEnabled := true, // enable SemanticDB,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
    libraryDependencies += "org.ow2.asm" % "asm" % "9.8",
    libraryDependencies += "org.apache.bcel" % "bcel" % "6.11.0",
    libraryDependencies += "com.github.scopt" %% "scopt" % "4.1.0",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test,
    libraryDependencies += "org.scalameta" %% "munit-scalacheck" % "0.7.29" % Test,
    libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.18.1" % Test,
    libraryDependencies += "com.github.erosb" % "everit-json-schema" % "1.14.6" % Test,
    libraryDependencies += "org.json" % "json" % "20250107" % Test,
    libraryDependencies += "com.google.guava" % "guava" % "33.6.0-jre" % Test,
    libraryDependencies += "commons-io" % "commons-io" % "2.18.0",
    libraryDependencies += "io.bullet" %% "borer-derivation" % "1.14.1",
    libraryDependencies += "com.palantir.isofilereader" % "isofilereader" % "0.6.1",
    libraryDependencies += "org.json4s" %% "json4s-native" % "4.0.7",
    libraryDependencies += "org.apache.commons" % "commons-compress" % "1.28.0",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.15",
    libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0",
    libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    libraryDependencies += "org.apache.tika" % "tika-core" % "3.2.3",
    // Config files. Kept in step with pom.xml, which declares the same two: the sbt and
    // Maven builds compile the same sources, so a dependency added to one and not the
    // other fails whichever build CI happens to run.
    libraryDependencies += "org.tomlj" % "tomlj" % "1.1.1",
    // The naming, layering and precedence rules every Spice component shares.
    libraryDependencies += "io.spicelabs" % "spice-config" % "1.0.0",
    // Still required at the boundary: the annatto/baharat readers hand back
    // com.github.packageurl.PackageURL, which we convert to coordinates.Purl.
    libraryDependencies += "com.github.package-url" % "packageurl-java" % "1.5.0",
    libraryDependencies += "io.spicelabs" %% "cilantro" % "0.1.17",
    // Canonical content identifiers (hashes + git blob ids) — the single source of
    // truth shared across Spice Labs tooling. Resolved from `Resolver.mavenLocal`.
    libraryDependencies += "io.spicelabs" % "coordinates" % "1.1.0",
    libraryDependencies += "com.github.dwickern" %% "scala-nameof" % "5.0.0" % "provided",

    // Spice Labs "readers"
    libraryDependencies += "io.spicelabs" % "baharat" % "0.1.1",
    libraryDependencies += "io.spicelabs" % "annatto" % "0.2.0",
    libraryDependencies += "io.spicelabs" % "saffron" % "0.4.0",
    libraryDependencies += "org.bouncycastle" % "bcprov-jdk18on" % "1.85.2",
    libraryDependencies += "org.bouncycastle" % "bcpkix-jdk18on" % "1.85",
    libraryDependencies += "org.bouncycastle" % "bcpg-jdk18on" % "1.85",
    libraryDependencies += "org.bouncycastle" % "bcutil-jdk18on" % "1.85",
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
  case PathList("META-INF", name)
      if name.endsWith(".SF") || name.endsWith(".DSA") ||
        name.endsWith(".RSA") || name
          .endsWith(".EC") || name.startsWith("SIG-") =>
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

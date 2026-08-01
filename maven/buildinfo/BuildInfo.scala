package hellogoat

/** Build metadata surfaced at runtime (version banner, ADG output).
  *
  * This is a TEMPLATE. The Maven build copies it through resource filtering
  * into `target/generated-sources/scala/hellogoat/BuildInfo.scala`, replacing
  * the `${...}` placeholders. It mirrors what sbt-buildinfo generates for the
  * sbt build, so the same `hellogoat.BuildInfo` object exists under either
  * build tool. Do not edit the generated copy.
  */
object BuildInfo {
  val name: String = "goatrodeo"
  val version: String = "${project.version}"
  val scalaVersion: String = "${scala.version}"
  val sbtVersion: String = "${sbt.version}"
  val commit: String = "${git.commit.id}"

  override def toString: String =
    s"name: $name, version: $version, scalaVersion: $scalaVersion, sbtVersion: $sbtVersion, commit: $commit"
}

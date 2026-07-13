package io.spicelabs.goatrodeo.util

import io.spicelabs.coordinates.Purl

/** Helpers related to Package URLs.
  *
  * Package URLs are produced via `io.spicelabs.coordinates.Purl`, the canonical
  * Spice Labs purl implementation (parse / build / normalize per the
  * purl-spec). `coordinates` exposes a plain constructor rather than a builder,
  * so [[purl]] is a small Scala-friendly factory over it.
  */
object PURLHelpers {

  /** Qualifiers for Maven packages
    */
  lazy val mavenQualifiers: Map[String, (String, String)] =
    Map(
      "pom" -> ("type", "pom"),
      "sources" -> ("packaging", "sources"),
      "javadoc" -> ("classifier", "javadoc")
    )

  /** Known ecosystems
    */
  enum Ecosystems {
    case Maven
    case Debian
  }

  /** Mapping between the Ecosystems and the text names and known qualifiers
    */
  lazy val ecosystems: Map[
    Ecosystems,
    (String, Some[Map[String, (String, String)]] | None.type)
  ] = Map(
    Ecosystems.Maven -> ("maven", Some(mavenQualifiers)),
    Ecosystems.Debian -> ("deb", None)
  );

  /** Build a [[Purl]] from its parts.
    *
    * Replaces the old `PackageURLBuilder`: `type` and `name` are required, the
    * rest optional. A blank namespace/version/subpath is treated as absent (the
    * canonical purl form distinguishes "missing" from "empty", and some types —
    * e.g. `nuget` — forbid a namespace entirely). The Scala API uses
    * `Option[String]`; `null` is only introduced at the Java constructor
    * boundary and does not leak into the Scala API.
    *
    * @param qualifiers
    *   key/value qualifiers, in insertion order (canonicalization sorts them)
    */
  def purl(
      `type`: String,
      name: String,
      namespace: Option[String] = None,
      version: Option[String] = None,
      qualifiers: Seq[(String, String)] = Seq(),
      subpath: Option[String] = None
  ): Purl = {

    /** Convert `Option[String]` to the Java `String` expected by
      * `coordinates.Purl`. This localizes `null` to the interop boundary so the
      * rest of the codebase does not need `String | Null`.
      */
    def optToJava[A](o: Option[A]): A = o match {
      case Some(v) => v
      case None    => null.asInstanceOf[A]
    }

    val q = new java.util.LinkedHashMap[String, String]()
    for ((k, v) <- qualifiers) q.put(k, v)

    new Purl(
      `type`,
      optToJava(namespace),
      name,
      optToJava(version),
      q,
      optToJava(subpath)
    )
  }

  /** Take a bunch of "information" and turn it into a package URL
    *
    * @param ecosystem
    *   the ecosystem
    * @param namespace
    *   the optional namespace (Maven uses this)
    * @param artifactId
    *   the artifact ID
    * @param version
    *   the artifact version
    * @param qualifierName
    *   the name of the qualifier that is mapped to the ecosystem-specific
    *   qualifier information
    * @param qualifiers
    *   any additional qualifiers
    * @return
    */
  def buildPackageURL(
      ecosystem: Ecosystems,
      namespace: Option[String] = None,
      artifactId: String,
      version: String,
      qualifierName: Option[String] = None,
      qualifiers: Seq[(String, String)] = Seq()
  ): Purl = {
    val (ecosystemText, ecosystemQualifiers) =
      ecosystems.get(ecosystem) match {
        case None => ("unknown", Map())
        case Some(name -> ecosystemQualifiers) =>
          (name, ecosystemQualifiers.getOrElse(Map()))
      }

    val namedQualifier =
      qualifierName.flatMap(name => ecosystemQualifiers.get(name)).toSeq

    purl(
      `type` = ecosystemText,
      name = artifactId,
      namespace = namespace,
      version = Some(version),
      qualifiers = namedQualifier ++ qualifiers
    )
  }

}

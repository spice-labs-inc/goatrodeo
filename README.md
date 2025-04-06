# Goat Rodeo

![Build Status](https://github.com/spice-labs-inc/goatrodeo/actions/workflows/scala_ci.yml/badge.svg)

## Quick Start

Goat Rodeo builds [Artifact Dependency Graphs](https://omnibor.io/glossary/#artifact-dependency-graph) from artifacts where
artifacts can be Docker images, JVM JAR/WAR files, Debian packages, and any other files including files that contain other files
such as Zip, TAR, AR, cpio, etc.

The basic operation is to `-b` build from a file or directory and `-o` output the ADG and found [Package URLs](https://github.com/package-url/purl-spec).

There are many [options to tune](info/goat_rodeo_operation.md) Goat Rodeo when building ADGs for a large number of files (tens of thousands,
millions) in a single batch.

### Docker

To run Goat Rodeo without installing a JVM or doing anything else other than having Docker, [Rancher](https://rancherdesktop.io/),
[Podman](https://podman.io/), or another container runner installed:

```shell
mkdir /tmp/goat_rodeo
docker run -ti --rm -v $(pwd)/target:/data/input -v /tmp/goat_rodeo:/data/output -u $(id -u ${USER}):$(id -g ${USER}) spicelabs/goatrodeo:0.7.0 -b /data/input -o /data/output
```

You can substitute whatever input location by replacing `$(pwd)/target` with the files that you want to build an ADG from.

You can substitute whatever output location by replacing `/tmp/goat_rodeo` with the appropriate output location.

### Running code natively

In order to run Goat Rodeo natively, you will need to install [JDK 21+](https://openjdk.org/projects/jdk/21/) and [sbt](https://www.scala-sbt.org/).

From the root of the [Goat Rodeo sources](https://goatrodeo.cc), build an "assembly": `sbt assembly`

Once the assembly is built, run Goat Rodeo: `java -jar target/scala-3.6.3/goatrodeo.jar -b target -o /tmp/goat_rodeo`

## Background

Using Inherent Identifiers (e.g., cryptographic hashes) to describe nodes in
[Artifact Dependency Graphs](https://omnibor.io/glossary/#artifact-dependency-graph) is the same
technique [Git](https://git-scm.com/) uses to identify branches, tags, etc.

Goat Rodeo builds an Artifact Dependency Graph of "things that contain other things". For example,
[Apache Directory](https://directory.apache.org/studio/download/download-linux.html) is a `tar.gz`...
a compressed [TAR](https://en.wikipedia.org/wiki/Tar_(computing)) file that contains a series of
JVM [JAR](https://docs.oracle.com/javase/tutorial/deployment/jar/basicsindex.html) file that
contains a series of [`.class`](https://en.wikipedia.org/wiki/Java_class_file) files. Goat
Rodeo recursively builds an artifact dependency graph that describes all encountered artifacts.

The recursive building of the Artifact Dependency Graph is called "Deep Inspection."

The artifact dependency graphs for all inspected artifacts is emitted and can be queried
and inspected using [Big Tent](https://github.com/spice-labs-inc/bigtent/).

This [Scala 3](https://docs.scala-lang.org/tour/tour-of-scala.html) code is built with the nominal Simple Build Tool [sbt](https://www.scala-sbt.org/)
and can be run on Java 21 or newer.

The code is licensed under and Apache 2.0 license and is intended to be used, shared, contributed to, etc.

## Hidden Reapers

There are hundreds of thousands of [Hidden Reapers](info/hidden_reapers.md) in the JVM ecosystem.
Sonatype has identified [336,000](https://www.sonatype.com/en/press-releases/sonatype-uncovers-millions-of-previously-hidden-open-source-vulnerabilities-through-unique-shaded-vulnerability-detection-system) across
Maven Central. Goat Rodeo can be used to "unmask" the Hidden Reapers in any JAR files.

## Building

**Goat Rodeo _requires_ [Git LFS](https://git-lfs.com/) to build**

Install [sbt](https://www.scala-sbt.org/) to build and run Goat Rodeo.

To create an "assembly" (a stand-alone executable JAR file): `sbt assembly`

The resulting JAR file can be executed: `java -jar target/scala-3.6.3/goatrodeo.jar`

To build an artifact dependency graph from local JAR files: `java -jar target/scala-3.6.3/goatrodeo.jar -b ~/.m2 -o /tmp/gitoidcorpus -t 24`

The above command tells the system to "build" (`-b`) the corpus from the JAR files in `~/.m2` and output the corpus
to the `/tmp/gitoidcorpus` directory using 24 threads. 

The resulting directory:

```shell
-rw------- 1 dpp dpp        76 Oct 10 13:25 2024_10_10_17_25_36_7cb8b580887ff6df.grc
-rw-rw-r-- 1 dpp dpp  14846423 Oct 10 13:25 6866766381176e9c.gri
-rw------- 1 dpp dpp 156324566 Oct 10 13:25 7c0d3cda0eff07c9.grd
-rw-rw-r-- 1 dpp dpp     20080 Oct 10 13:25 purls.txt
```

The `purls.txt` file contains the [Package URLs](https://github.com/package-url/purl-spec) of
the discovered packages.


## Development

Goat Rodeo's tests require a set of large files. They are downloaded from a CDN
and cached locally during test runs.

This will be a set of files that Goat Rodeo will index as part of it's normal tests.

In the same directory (dpp uses `~/proj/`) that you cloned Goat Rodeo, also clone
[Big Tent](https://github.com/spice-labs-inc/bigtent).

When you run the Goat Rodeo tests, a `res_for_big_tent` directory is created that contains
generated index files. When you run Big Tent tests, the tests look for `../goatrodeo/res_for_big_tent`
and the generated files.

This directory is deleted and re-built on every test run.

Then use Cargo to build Big Tent: `cargo build`

From the Big Tent directory, you can run Big Tent against the data set: `./target/debug/bigtent -r /tmp/ff/<generated>.grc`

With Big Tent running, you can curl: `curl -v http://localhost:3000/omnibor/sha256:7c7b1dee41ae847f0d8aa66faf656f5f2fe777e4656db9fe4370d2972070c62b
`

That looks up the SHA256 value in the Corpus:

```json
{

  "alt_identifiers": [],
  "connections": [
    [ "AliasTo",
      "gitoid:blob:sha256:4a176f25ca66f78f902082466d2e64bbb3ce5db3a327f006d48dc17a6fb58784"
    ]
  ],
  "identifier": "sha256:7c7b1dee41ae847f0d8aa66faf656f5f2fe777e4656db9fe4370d2972070c62b",
  "merged_from": [],
  "metadata": null,
  "previous_reference": null,
  "reference": [
    8847588522402379377,
    938
  ]
}
```

The `AliasTo` points to the actual GitOID which can also be fetched: `curl -v http://localhost:3000/omnibor/gitoid:blob:sha256:4a176f25ca66f78f902082466d2e64bbb3ce5db3a327f006d48dc17a6fb58784`

Have fun!


## Participating

We welcome participation and have a [Code of Conduct](code_of_conduct.md).


# Finding Silent Reaper artifacts based on the Grim List

Spice Labs' OmniBOR Corpus contains the [gitoids](https://www.iana.org/assignments/uri-schemes/prov/gitoid)
for artifacts in many different open source ecosystems including the JVM/Java ecosystem.

An artifact is an array of bytes. An artifact may be a Java `.class` file, it may be an
image, it may be a Java [JAR](https://docs.oracle.com/javase/tutorial/deployment/jar/basicsindex.html)
file, it may be a `.deb` package, etc.

Spice Labs has created a graph of more than 2B vertices describing the relationship
among many open source artifacts.

## JAR files and Dependencies

The most prevelant mechanism for for building and packaging Java (and Scala/Kotlin/etc.)
programs involed using [Maven](https://maven.apache.org/) or a compatible tool
(e.g., [sbt](https://www.scala-sbt.org/), [Gradle](https://gradle.org/)).

These tools allow engineers to define the packages their programs depend
on. These packages are fetched from an artifact repository (e.g., [Maven Central](https://central.sonatype.com/?smo=true))
and made available as part of the build and packaging process.

When an engineer declares dependencies in the build tool, those
dependencies are listed and can be used to check if a particular
artifact depends on any other artifacts with known vulnerabilities.

However, sometimes engineers copy/paste source code from projects into their
project. When projects with copied/pasted code is built, the dependencies
are silent and hidden.

When these silent dependencies have **critical** vulnerabilities,
we call this "Silent Reaper".

## Markers

"Markers" are the artifacts unique to a particular artifact. What?

Let's say we have a package, Foo that has three versions, 1.0, 1.1, and
1.2. Package Foo contains three artifacts: Foo:1.0, Foo:1.1, Foo:1.2.

The artifacts are made up of artifacts... class files, license files, etc.
all rolled together in a JAR file (we're in JVM-land).

The three artifacts will share many artifacts: class files derived
from source files that are stable across all three versions, etc.

However, there will be artifacts (e.g., `.class` files) that are unique
to each version of Foo. These are "markers."

If these "marker" artifacts are contained by other artifacts, those
containing artifacts will very likely _silently_ contain a particular
version of Foo.

## Detecting Silent Reapers

Spice Labs has taken GitHub's [Advisory Database](https://github.com/advisories)
and found all Java/JVM packages that have critical vulnerabilities.

For each artifact (package + version), the vulnerabilities for that artifact
have been pulled from [OSV](https://osv.dev/).

Artifacts with critical vulnerabilities are Reapers.

For each artifact in each package with critical vulnerabilities, Spice Labs has computed
the "markers" for that artifact.

Spice Labs then uses these markers to locate other artifacts
that Silently contain the Reapers.

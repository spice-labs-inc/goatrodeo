# Goat Rodeo

The Software Supply Chain is a big gnarly mess of unknowns... and it's
baked right into all the systems you and your company build an run. 😱

[Goat Rodeo](https://www.urbandictionary.com/define.php?term=Goat%20%20Rodeo)
is a bit of an attempt to reign in the chaos that is the software supply chain.

Based on the specs and tenits of [OmniBOR](https://omnibor.io), Goat Rodeo
creates and queries the set of [gitoids](https://www.iana.org/assignments/uri-schemes/prov/gitoid)
for a container (starting with Java [JAR](https://docs.oracle.com/javase/tutorial/deployment/jar/basicsindex.html) files)
and the container's contents.

The indexed collection of GitOIDs is called a "GitOID Corpus". For example: [Log4J version 2.7](https://goatrodeo.org/omnibor/gitoid:blob:sha256:f14a09c612371efe86ff8068e9bf98440c0d59f80e09df1753303fe6b25dd994).
The Entry in the corpus points to many other gitoids... the classfiles and other contents of the package. This
includes [AbstractAction.class](https://goatrodeo.org/omnibor/gitoid:blob:sha256:76375cae82efa98bc0607b8a59b05e3ae05093a834fef2cede707a6537d78857)
which is contained by 12 different pacakges. The bidirectional index of the Corpus allows
discovery of the contents of unknown JAR files.

This [Scala 3](https://docs.scala-lang.org/tour/tour-of-scala.html) code is built with the nominal Simple Build Tool [sbt](https://www.scala-sbt.org/)
and can be run on Java 17 or newer.

The code is licensed under and Apache 2.0 license and is intended to be used, shared, contributed to, etc.

## Why?

We use Open Source software in every one of our systems. Yet, we don't know
the contents of what we use or build... and we don't understand the provenance of what
we use.

OmniBOR defines cryptographic mechanisms for identifying and building [directed acyclic graphs](https://en.wikipedia.org/wiki/Directed_acyclic_graph) (DAGs)
of software artifacts... from source code through intermediate artifacts (`.class` files, `.so` files, etc.) all the way through to
executables.

However, creating OmniBOR DAGs and the associated [Merkle trees](https://en.wikipedia.org/wiki/Merkle_tree) requires some integration
with tooling (compilers, linkers, packaging tools, etc.) However that integration, as of January 2024, has not materialized.

But it's possible to compute GitOIDs and DAGs for already published artifacts including Java JAR files storage in 
[Maven Central](https://maven.apache.org/repository/index.html). With that information, it's possible
to look at the composition of a JAR file and list potential CVEs in the code in the JAR file as
shown in this [demo](https://www.youtube.com/watch?t=201&v=RDFeJwK088U&feature=youtu.be).

The value is that the contents of existing JAR and [WAR](https://en.wikipedia.org/wiki/WAR_(file_format)) can be
inspected against the Goat Rodeo GitOID Corpus to determine the contents and the potential vulnerabilities in the contents.

## What?

The Goat Rodeo code will generate GitOID Corpus segments from collections of JAR files.

## How?

Install [sbt](https://www.scala-sbt.org/) to build and run Goat Rodeo.

To create an "assembly" (a stand-alone executable JAR file): `sbt assembly`

The resulting JAR file can be executed: `java -jar target/scala-3.3.1/goatrodeo.jar`

To build a local GitOID Corpus from local JAR files: `java -jar target/scala-3.3.1/goatrodeo.jar -b ~/.m2 -o /tmp/gitoidcorpus -t 24`

The above command tells the system to "build" (`-b`) the corpus from the JAR files in `~/.m2` and output the corpus
to the `/tmp/gitoidcorpus` directory using 24 threads. 

## Participating

We welcome participation and have a [Code of Conduct](code_of_conduct.md).

Discussions on [Matrix](https://matrix.to/#/#spice-labs:matrix.org)

Looking forward to making the dependencies in your projects a bit less of a Goat Rodeo.

# Flow: How Goat Rodeo Processes Directories and Files

This document describes the "flow" that Goat Rodeo uses
to go from looking at a directory to generating Artifact
Dependency Graphs (ADGs) for the collections of files in the directories.

This is for the main "Build" use case in Goat Rodeo
and may or may not apply to other start-up options.

## Strategies?

Goat Rodeo will build ADGs for a wide variety of different
artifact types and directory layouts. For example,
Goat Rodeo may be building ADGs for JVM JAR files
in a Maven repository or it may be building an ADG
for a single Rust project (source, dependencies, compiled binary).

Thus, when Goat Rodeo looks at a directory, subdirectories, files,
it should choose a strategy for how to deal with the collection
of files in order to yield the correct way to traverse the directories.

In the case of JVM code, if a `.pom` , a `-sources.jar`, and a `.jar`
file all have the same root name, these files will be collected together
such that the metadata, especially the metadata used to compute the
[Package URL](https://github.com/package-url/purl-spec) (pURL)
will be grouped together and processed in order:

* Process the pom file to get pURL and other metadata
* Process the sources file
* Process the JAR file and associate the sources file and the
  `.class` files

In the case of a Rust project, computing metadata and pURL
from the `Cargo.toml` file, looking at the `src` directory,
and then the `target` directory for both built artifacts and
dependencies is part of the strategy. And this strategy may
reference files outside of the directory hierarchy as the `.d`
files that describe dependencies may point to directories outside
of the initial directory hierarchy.

There will be separate strategies for:

* NodeJS project to capture pre- and post minified artifacts
* Ruby/Rails
* Python

## Developing Strategies

When Goat Rodeo is started with the "build" command, it
traverses the supplied directories and for each file encountered,
Goat Rodeo will build an `ArtifactWrapper` that includes
the mime type of the file as determined by Apache Tika with
possible additional plugins.

Note, certain files (e.g., files that start with `.` may be excluded from the traversal).

There will also be two Maps generated: `Map[filename_as_string, Vector[ArtifactWrapper]]`
and `Map[relative_path_as_string, Vector[ArtifactWrapper]]`.

These maps will be passed to each of the strategy selectors and
the selectors will return a `Vector[GoatRodeoStrategy]` and
`Vector[ArtifactWrapper]` with each of the `ArtifactWrappers` that
was consumed by the strategy.

Each of the `ArtifactWrapper`s that were not consumed by a strategy
will be wrapped in a `SingleFileStrategy` wrapper.

As the Strategy instances are created, they will be put on a queue for
consumption/processing by separate threads.

## Processing each `GoatRodeoStrategy`

The Strategies will be put on a queue... and pulled off by separate processing threads.
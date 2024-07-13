# File Format for the OmniBOR Corpus

The [OmniBOR](https://omnibor.dev) Corpus provides a graph of relationships
among software artifacts. A software artifact may be a source file, it
may be an intermediate file (e.g., a class file, a `.o`, etc.) or
it may be a collections of entities (e.g., a JAR file, an RPM or DEB package).

By associating a [gitoid](https://www.iana.org/assignments/uri-schemes/prov/gitoid),
a [pURL](https://github.com/package-url/purl-spec) or another identifier (e.g., a raw
hash [SHA1, SHA256, MD5, etc.]) with an [Entry](#entry), an external system can
walk the graph to associate artifacts with packages to determine composition.

## Corpus Size

The OmniBOR Corpus for Maven Central contains more than 300M entries and the corpus
size is nearly 1 terabyte. Once other sources (e.g., DockerHub, the Debian/Ubuntu packages,
etc.) the corpus will grow to billions of entries over many terabytes.

This also implies that sharding the corpus will be required.

## The Corpus is a Graph

The OmniBOR Corpus represents a [Graph](https://en.wikipedia.org/wiki/Graph_database) of a
relationships among Entries. Graph databases are notoriously hard to scale.

## Corpus Access Pattersn

Thus, the access pattern for the Corpus will be a series of gitoid or pURL or
other fetch operations. And to determine the composition of a JAR file with 5,000
classes, there may be 10,000+ fetches as the graph is walked from the found
items to items that contain those items.

Further, access must be by gitoid, pURL, or some other lookup mechanism (e.g., raw hash).
Thus, a file format cannot be based solely on the characteristics of the gitoid URL.

## Corpus Evolution

The OmniBOR Corpus is a live database that will be continuously updated. Thus,
adding/merging records must be an efficient operation, preferably O(n).

## Corpus Distribution

The OmniBOR Corpus will be distributed to many mirror sites and other places.

In order to reasonably distribute the Corpus, only change sets should be
distributed and there must be cryptographic verification that the mirrored
Corpus is identical to the master.

## Discussion

Text files are good.

Text files can be manipulated by a lot of mature tools including [diff](https://www.man7.org/linux/man-pages/man1/diff.1.html), 
[grep](https://man7.org/linux/man-pages/man1/grep.1.html), [split](https://man7.org/linux/man-pages/man1/split.1.html),
[sha256sum](https://man7.org/linux/man-pages/man1/sha256sum.1.html), etc.

Text files are fast to access with [seek](https://man7.org/linux/man-pages/man2/lseek.2.html).

The OmniBOR Corpus will be presented as a text file where each line is an entry with the following format:

`<md5 hash of URL>,<URL>||,||<json of entry>`

The first 32 bytes of the line contains the hex of [md5](https://en.wikipedia.org/wiki/MD5) the URL for the line.

The characters between the `,` and the `||,||` separator is the URL of the entry. The URL may be the gitoid, it may
be the pURL, or it may be another lookup mechanism.

Finally, the "stuff" after the `||,||` separator contains a compact JSON representation of the [Entry](#entry).
Compact JSON contains no line separators and the contents of JSON strings escape both control characters and
non-ASCII characters.

The string `||,||` was chosen as it's unlikely to appear in the URL and it offers the possibility of changing
the `,` to another character to indicate some other format for the line. While there's no current plan to
indicate anything other than an Entry, the posibility exists.

The OmniBOR Corpus is stored in sorted order by MD5 hash (the first 32 characters of the line).

The Corpus may be sharded by the first (16 shards), second (256 shards), or third (4096 shards)
character of the line.

Because each Entry contains the "edges" or pointers to other Entries (the `contains` and `containedBy` fields),
the Corpus is trading size (larger) for the ability to shard.

Because the Corpus is always kept in sorted order, merging new Entries into the Corpus is
an O(n) operation. The algorithm is simple:

1. Load the next Entry from all the sources for merging (the main Corpus and each new-to-add sub-Corpus)
2. Find the lowest MD5 hash for the Entries
3. If there's only one Entry with the same hash, write, load next Entry for that file, go to step 2
4. If there are more than one Entry for a given hash, merge the Entries, write the merged entry, load next Entry for the files that were merged, go to step 2

How does this help with serving the OmniBOR Corpus?

In order to serve the corpus, the 16 bytes of the MD5 hash and the 8 bytes of the file offset must be kept in
memory on the server. Thus, the index will always be in RAM. For a Corpus with 300M entries, that's about 7GB
of RAM.

[BigTent](https://gitlab.com/spicelabs1/bigtent) can create an index and save it to disk. For a 7GB index,
it takes BigTent about 4 seconds to load the index.

On a machine with a fast processor (e.g., Ryzen 9) and NVMe storage, BigTent can serve more than 400
entries per second, saturating a 1gig NIC. Serving individual Entries is a sub-millisecond operation.

While BigTent doesn't yet support sharding, the algorithm is a simple addition to BigTent:

* Is the MD5 hash in this shard? Yes, load and serve it.
* Determine which machines the hash is in, make the request on that machine, serve the response.

So, sharding will add 1 hop to the process of serving an Entry.

## Entry

<p id="entry">Data Shapes</p>

```scala
case class Entry(
    identifier: GitOID,
    contains: Vector[GitOID],
    containedBy: Vector[GitOID],
    metadata: EntryMetaData,
)
```

```scala
case class EntryMetaData(
    filename: Option[String] = None,
    purl: Option[String] = None,
    vulnerabilities: Option[ujson.Value],
    filetype: Option[String] = None,
    filesubtype: Option[String] = None,
    contents: Option[String] = None,
    other: Option[ujson.Value],

)
```
# Goat Rodeo

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/goatrodeo_3?label=Maven%20Central)](https://central.sonatype.com/artifact/io.spicelabs/goatrodeo_3)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/goatrodeo?label=GitHub%20Release)](https://github.com/spice-labs-inc/goatrodeo/releases)
[![GitHub Package](https://img.shields.io/badge/GitHub-Packages-blue?logo=github)](https://github.com/spice-labs-inc/goatrodeo/packages/)
[![Docker Image Version (latest by date)](https://img.shields.io/docker/v/spicelabs/goatrodeo?sort=date&label=Docker%20Hub)](https://hub.docker.com/r/spicelabs/goatrodeo)

**Goat Rodeo** is an open-source tool that constructs **Artifact Dependency Graphs (ADGs)** from software artifacts.  
It powers [Spice Labs CLI](https://github.com/spice-labs-inc/spice-labs-cli) and implements the [OmniBOR](https://omnibor.io) approach to content-addressable software graphs.

---

## 📦 Getting Started

### Maven Usage

Add Goat Rodeo to your project:

```xml
<dependency>
  <groupId>io.spicelabs</groupId>
  <artifactId>goatrodeo_3</artifactId>
  <version>0.8.1</version>
</dependency>
```

If not yet synced to Maven Central, you can use GitHub Packages:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/spice-labs-inc/goatrodeo</url>
  </repository>
</repositories>
```

---

### CLI via Docker

```bash
mkdir /tmp/goat_rodeo
docker run -ti --rm   -v $(pwd)/target:/data/input   -v /tmp/goat_rodeo:/data/output   -u $(id -u):$(id -g)   ghcr.io/spice-labs-inc/goatrodeo:0.7.0   -b /data/input -o /data/output
```

---

### Java Usage

```java
GoatRodeo.builder()
  .withPayload("/path/to/artifacts")
  .withOutput("/path/to/output")
  .withThreads(8)
  .withMaxRecords(100000)
  .run();
```

---

## 🔍 What is an ADG?

An **Artifact Dependency Graph (ADG)** is a deterministic, content-addressable graph that maps all software inputs recursively.  
This includes `.jar` → `.class`, nested archives, Docker image layers, etc. Goat Rodeo uses GitOID-style hashing for full verifiability.

---

## 🔎 Query with Big Tent

1. Clone and build Big Tent:
```bash
git clone https://github.com/spice-labs-inc/bigtent.git
cd bigtent
cargo build
```

2. Launch Big Tent:
```bash
./target/debug/bigtent -r /tmp/gitoidcorpus/<corpus>.grc
```

3. Query:
```bash
curl http://localhost:3000/omnibor/sha256:<hash>
```

---

## 🛠️ Maintainers

### Build Requirements

- Goat Rodeo uses **Git LFS**. Install it before cloning or building:
  ```bash
  git lfs install
  git clone https://github.com/spice-labs-inc/goatrodeo.git
  ```

---

### Build from Source

Goat Rodeo requires **JDK 21+**, **Scala 3**, and **sbt**:

```bash
cd goatrodeo
sbt assembly
```

Outputs fat JAR at:
```
target/scala-3.6.3/goatrodeo.jar
```

Run locally:
```bash
java -jar target/scala-3.6.3/goatrodeo.jar -b ~/.m2 -o /tmp/gitoidcorpus -t 24
```

---

### Full CLI Reference

```text
-b, --build <dir>             Build ADG from this directory (recursive)
-o, --out <dir>               Output directory
-t, --threads <int>           Number of threads (default 4)
--tag <text>                  Add tag metadata to output
--file-list <file>            File containing list of paths to include
--ignore <file>               File of paths to ignore (previously processed)
--ingested <file>             Log successful processed inputs
--exclude-pattern <regex>     Regex pattern to exclude files
--block <file>                GitOID blocklist (e.g. license gitoids)
--tempdir <dir>               Temp dir (RAM disk recommended)
--maxrecords <int>            Max records to process (default 50000)
-V, --version                 Print version and exit
-?, --help                    Print help and exit
```

---

### Releasing

1. **Create a GitHub release**  
   Tag with `v0.x.y`. GitHub Actions will build and publish to GitHub Packages and Maven Central.

2. **Monitor Maven Central** (optional)  
   Visit [https://central.sonatype.com](https://central.sonatype.com) → `Publish → Deployments`  
   Publishing takes ~40 minutes.

3. **Verify the release**:

```bash
mvn dependency:get -Dartifact=io.spicelabs:goatrodeo_3:0.8.1
```

Artifacts include:

- GitHub Release: [releases](https://github.com/spice-labs-inc/goatrodeo/releases)
- GitHub Packages: [Packages](https://github.com/spice-labs-inc/goatrodeo/packages)
- Maven Central: [central.sonatype.com](https://central.sonatype.com/artifact/io.spicelabs/goatrodeo_3)
- Docker Hub: [hub.docker.com](https://hub.docker.com/r/spicelabs/goatrodeo)

---

## 📜 License

Apache 2.0  
© 2025 Spice Labs, Inc. & Contributors%       
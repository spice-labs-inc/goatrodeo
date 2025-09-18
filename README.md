# Goat Rodeo

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/goatrodeo_3?label=Maven%20Central)](https://central.sonatype.com/artifact/io.spicelabs/goatrodeo_3)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/goatrodeo?label=GitHub%20Release)](https://github.com/spice-labs-inc/goatrodeo/releases)
[![GitHub Package](https://img.shields.io/badge/GitHub-Packages-blue?logo=github)](https://github.com/spice-labs-inc/goatrodeo/packages/)
[![Docker Image Version (latest by date)](https://img.shields.io/docker/v/spicelabs/goatrodeo?sort=date&label=Docker%20Hub)](https://hub.docker.com/r/spicelabs/goatrodeo)

**Goat Rodeo** is an open-source tool from [Spice Labs](https://spicelabs.io) that constructs **Artifact Dependency Graphs (ADGs)** from software artifacts using [OmniBOR](https://omnibor.io) content-addressable identifiers. It can be run standalone or as part of the [Spice Labs CLI](https://github.com/spice-labs-inc/spice-labs-cli).

Supported artifact types include: `TAR`, `ZIP`, `JAR`, `class` files, `ISO`, `AR`, `cpio`, and Docker images.

---

## 🧠 What It Does

Given a starting directory, Goat Rodeo recursively scans and processes its contents to generate:

- A **Gitoid database** of artifacts
- An **ADG (Artifact Dependency Graph)** in JSON format
- An optional **ingestion log** for tracking processed files

The ADG represents relationships between software components, enabling deep insights for supply chain visibility, reproducibility, and security analysis.

---

## 📦 How to Use Goat Rodeo

You can use Goat Rodeo in one of three ways:

### 1️⃣ Run as a Docker Image

```bash
docker run -ti --rm \
  -v ~/tmp/goat_rodeo/data/input:/data/input \
  -v ~/tmp/goat_rodeo/data/output:/data/output \
  -u $(id -u):$(id -g) \
  spicelabs/goatrodeo:latest \
  -b /data/input \
  -o /data/output
```
**Note:** a typical Docker installation will require that this command must be run as root (not recommended), or with an appropriate group configuration. See [here](https://docs.docker.com/engine/install/linux-postinstall/) for more details. 

---

### 2️⃣ Build and Run as a Java Program

**Requirements:** Git LFS, Java 21+, Scala 3, and `sbt`

```bash
git lfs install
git clone https://github.com/spice-labs-inc/goatrodeo.git
cd goatrodeo
sbt assembly
```

Produces fat JAR at:

```
target/scala-3.7.2/goatrodeo-0.0.1-SNAPSHOT-fat.jar
```

Run example:

```bash
java -jar target/scala-*/goatrodeo-*-fat.jar -b ~/.m2 -o /tmp/gitoidcorpus -t 24
```
**Note:** the `-b` flag directs goat rodeo to start searching in a given directory which much exist. Otherwise goatrodeo will exit with an error.
---

### 3️⃣ Use as a Java Library (via Maven)

Add the following to your project's `pom.xml` in the `<dependencies>` section:

```xml
<dependency>
  <groupId>io.spicelabs</groupId>
  <artifactId>goatrodeo_3</artifactId>
  <version>0.8.4</version>
</dependency>
```

Example usage:

```java
GoatRodeo.builder()
  .withPayload("/path/to/artifacts")
  .withOutput("/path/to/output")
  .withThreads(8)
  .withMaxRecords(100000)
  .run();
```

---

## 🛠️ CLI Options

| Option                      | Description |
|----------------------------|-------------|
| `--block <value>`          | Gitoid block list. Skips common gitoids like license files. |
| `-b`, `--build <value>`    | Build gitoid database from directory of JAR files. |
| `--tag <value>`            | Tag top-level artifacts with a label and timestamp. |
| `--ingested <value>`       | Append successfully processed files to this file. |
| `--ignore <value>`         | File containing paths to skip (e.g., previously processed). |
| `--file-list <value>`      | File containing list of specific files to process. |
| `--exclude-pattern <value>`| Regex pattern to exclude files (e.g., `html$`). |
| `--maxrecords <value>`     | Max records to process at once (default: 50,000). |
| `-o`, `--out <value>`      | Output directory for gitoid database and results. |
| `--tempdir <value>`        | Directory for temporary storage (RAM disk recommended). |
| `-t`, `--threads <value>`  | Number of threads (default: 4). Suggest 2–3× CPU cores. |
| `-V`, `--version`          | Print version and exit. |
| `-?`, `--help`             | Print help and exit. |

---

## 🚀 Release and Maintenance

### Releasing a New Version

1. **Create a GitHub Release**
   - Tag it with `v0.x.y`
   - Triggers GitHub Actions to publish to:
     - GitHub Packages
     - Maven Central

2. **Monitor Maven Central**
   - [central.sonatype.com → Deployments](https://central.sonatype.com)
   - Publishing may take ~40 minutes

3. **Verify via Maven**:

```bash
mvn dependency:get -Dartifact=io.spicelabs:goatrodeo_3:0.8.4
```

Published artifacts:

- [GitHub Releases](https://github.com/spice-labs-inc/goatrodeo/releases)
- [GitHub Packages](https://github.com/spice-labs-inc/goatrodeo/packages)
- [Maven Central](https://central.sonatype.com/artifact/io.spicelabs/goatrodeo_3)
- [Docker Hub](https://hub.docker.com/r/spicelabs/goatrodeo)

---

## 📜 License

Apache License 2.0  
© 2025 [Spice Labs, Inc.](https://spicelabs.io) & Contributors
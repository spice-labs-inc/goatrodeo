<div align="center">

# Goat Rodeo

**Build Artifact Dependency Graphs for Software Supply Chain Transparency**

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/goatrodeo_3?label=Maven%20Central&logo=apache-maven)](https://central.sonatype.com/artifact/io.spicelabs/goatrodeo_3)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/goatrodeo?label=Release&logo=github)](https://github.com/spice-labs-inc/goatrodeo/releases)
[![Docker](https://img.shields.io/docker/v/spicelabs/goatrodeo?sort=date&label=Docker&logo=docker)](https://hub.docker.com/r/spicelabs/goatrodeo)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE.txt)
[![CI](https://img.shields.io/github/actions/workflow/status/spice-labs-inc/goatrodeo/ci.yml?label=CI&logo=github-actions)](https://github.com/spice-labs-inc/goatrodeo/actions)

[Getting Started](#-getting-started) · [Documentation](info/README.md) · [Contributing](CONTRIBUTING.md) · [Community](#-community)

</div>

---

## What is Goat Rodeo?

Goat Rodeo is an open-source tool that analyzes software artifacts and builds **Artifact Dependency Graphs (ADGs)** using [OmniBOR](https://omnibor.io) content-addressable identifiers. It answers questions like:

- *"What components are inside this JAR/Docker image/package?"*
- *"Which artifacts share this vulnerable library?"*
- *"Where did this binary come from?"*

```
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│   Artifacts │ ──▶  │ Goat Rodeo  │ ──▶  │     ADG     │
│  JAR/DEB/   │      │  Analysis   │      │   Database  │
│  Docker/... │      │             │      │  (.grd/gri) │
└─────────────┘      └─────────────┘      └─────────────┘
```

---

## Features

| | Feature | Description |
|---|---------|-------------|
| 📦 | **Multi-format Support** | JAR, WAR, EAR, TAR, ZIP, DEB, APK, Docker images, ISO, NuGet packages (.nupkg), .NET assemblies |
| 🔍 | **Deep Inspection** | Recursively unpacks nested archives (JAR inside TAR inside ISO) |
| ⚡ | **Parallel Processing** | Multi-threaded analysis for large artifact sets |
| 🔗 | **Bidirectional Graph** | Query both "what contains X" and "what does X contain" |
| 🛡️ | **Hidden Reaper Detection** | Find vulnerabilities hidden from traditional SCA tools |
| 📊 | **pURL Support** | Generates Package URLs for ecosystem compatibility |
| 🔌 | **Embeddable** | Use as CLI tool, Docker container, or Java/Scala library |

---

## Getting Started

### Option 1: Docker (Recommended)

```bash
docker run --rm \
  -v /path/to/artifacts:/input:ro \
  -v /path/to/output:/output \
  spicelabs/goatrodeo:latest \
  -b /input -o /output
```

> **Note:** Docker typically requires root privileges or membership in the `docker` group. See [Docker post-installation steps](https://docs.docker.com/engine/install/linux-postinstall/) for configuration details.

### Option 2: Download Release

```bash
# Download latest release
curl -LO https://github.com/spice-labs-inc/goatrodeo/releases/latest/download/goatrodeo-fat.jar

# Run
java -jar goatrodeo-fat.jar -b /path/to/artifacts -o /path/to/output
```

### Option 3: Build from Source

```bash
git clone https://github.com/spice-labs-inc/goatrodeo.git
cd goatrodeo
sbt assembly
java -jar target/scala-3.7.4/goatrodeo-*-fat.jar -b /path/to/artifacts -o /path/to/output
```

> **Requirements:** Java 21+, Git LFS

### Option 4: As a Library

**Maven:**
```xml
<dependency>
  <groupId>io.spicelabs</groupId>
  <artifactId>goatrodeo_3</artifactId>
  <version>0.8.4</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.spicelabs:goatrodeo_3:0.8.4'
```

**Usage:**
```java
import io.spicelabs.goatrodeo.GoatRodeo;

GoatRodeo.builder()
    .withPayload("/path/to/artifacts")
    .withOutput("/path/to/output")
    .withThreads(8)
    .run();
```

---

## CLI Reference

```bash
goatrodeo [OPTIONS]
```

### Essential Options

| Option | Description |
|--------|-------------|
| `-b, --build <dir>` | Directory containing artifacts to analyze |
| `-o, --out <dir>` | Output directory for ADG database |
| `-t, --threads <n>` | Parallel threads (default: 4) |

### Filtering

| Option | Description |
|--------|-------------|
| `--file-list <file>` | Only process files listed here |
| `--ignore <file>` | Skip paths listed here |
| `--exclude-pattern <regex>` | Exclude matching files |
| `--mime-filter <filter>` | Filter by MIME type (`+include`, `-exclude`) |

### Advanced

| Option | Description |
|--------|-------------|
| `--maxrecords <n>` | Batch size (default: 50,000) |
| `--tempdir <dir>` | Temp storage (RAM disk recommended) |
| `--tag <name>` | Tag this run for later identification |
| `--block <file>` | Skip known/common GitOIDs |

<details>
<summary><b>Performance Tips</b></summary>

For large artifact sets (10,000+ files):

1. **Use a RAM disk** for temp files:
   ```bash
   sudo mount -t tmpfs -o size=25G tmpfs /mnt/ramdisk
   goatrodeo -b /artifacts -o /output --tempdir /mnt/ramdisk
   ```

2. **Match threads to CPU cores** (or fewer if memory-constrained)

3. **Tune batch size** with `--maxrecords` based on available RAM

See [Performance Tuning Guide](info/goat_rodeo_operation.md#tuning-for-performance) for details.

</details>

---

## Documentation

| Document | Description |
|----------|-------------|
| 📖 [Documentation Index](info/README.md) | Complete documentation hub |
| ⚙️ [How It Works](info/goat_rodeo_operation.md) | Processing pipeline & tuning |
| 🔧 [API Reference](info/goat_rodeo_api.md) | Library integration guide |
| 🏗️ [Architecture](info/architecture.md) | Internals for contributors |
| 🛡️ [Hidden Reapers](info/hidden_reapers.md) | Finding hidden vulnerabilities |

---

## Use Cases

### Software Composition Analysis
Identify all components in your artifacts, even those not declared in manifests or build files.

### Vulnerability Detection
Find [Hidden Reapers](info/hidden_reapers.md) — vulnerabilities that traditional SCA tools miss because dependencies were copied rather than declared.

### License Compliance
Trace every component back to its source to ensure license obligations are met.

### Supply Chain Security
Build a cryptographic inventory of your software supply chain with content-addressable identifiers.

---

## Community

- 💬 **Chat:** [Matrix #spice-labs](https://matrix.to/#/#spice-labs:matrix.org)
- 🐛 **Issues:** [GitHub Issues](https://github.com/spice-labs-inc/goatrodeo/issues)
- 📣 **Discussions:** [GitHub Discussions](https://github.com/spice-labs-inc/goatrodeo/discussions)

---

## Contributing

We welcome contributions! See our [Contributing Guide](CONTRIBUTING.md) for details.

```bash
# Clone with LFS support
git lfs install
git clone https://github.com/spice-labs-inc/goatrodeo.git

# Run tests
cd goatrodeo
sbt test

# Submit a PR against the `next` branch
```

---

## Related Projects

- [OmniBOR](https://omnibor.io) — The specification for artifact identifiers
- [Spice Labs CLI](https://github.com/spice-labs-inc/spice-labs-cli) — Full Spice Labs toolchain
- [BigTent](https://gitlab.com/spicelabs1/bigtent) — ADG serving infrastructure

---

## License

Apache License 2.0 — see [LICENSE.txt](LICENSE.txt)

---

<div align="center">

**[Spice Labs](https://spicelabs.io)**

[Website](https://spicelabs.io) · [LinkedIn](https://www.linkedin.com/company/spice-labs-inc)

</div>

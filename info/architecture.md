# Goat Rodeo Architecture

This document describes the internal architecture of Goat Rodeo for developers who want to understand, modify, or extend the codebase.

---

## Overview

Goat Rodeo processes software artifacts to build Artifact Dependency Graphs (ADGs). The system is designed for:

- **Parallel processing** - Multiple artifacts processed simultaneously
- **Extensibility** - New artifact types via strategy pattern
- **Memory efficiency** - Streaming where possible, temp files for large artifacts
- **Low-lock concurrency** - Minimal contention in shared data structures

---

## Directory Structure

```
src/main/scala/io/spicelabs/goatrodeo/
├── Main.scala              # CLI entry point (Howdy object)
├── GoatRodeoBuilder.scala  # Programmatic API
├── omnibor/                # Core ADG building logic
│   ├── Builder.scala       # Orchestrates the build process
│   ├── Item.scala          # ADG node representation
│   ├── Storage.scala       # In-memory graph storage
│   ├── GraphManager.scala  # File I/O for .grd/.gri files
│   ├── ToProcess.scala     # Processing strategy framework
│   ├── Struct.scala        # Data structures (EdgeType, metadata)
│   └── strategies/         # Artifact-specific processors
│       ├── Maven.scala     # JAR/POM processing
│       ├── Docker.scala    # Docker image layers
│       ├── Debian.scala    # .deb packages
│       ├── Dotnet.scala    # .NET assemblies
│       ├── Annatto.scala   # Bun JS bundler outputs
│       ├── Baharat.scala   # Saffron-flagged outputs
│       ├── JvmDistribution.scala  # JDK/JRE release-file detection
│       ├── GradleLockfile.scala  # Gradle lockfile dependency parsing
│       ├── Certificates.scala       # X.509/CRL/keystore/SSH/PGP/private-key
│       ├── CertificatesState.scala  # Per-artifact processing state for ^
│       ├── CertificatesOidMaps.scala # OID lookup tables for ^
│       ├── OpenSSLConfig.scala      # OpenSSL .cnf capture
│       ├── JavaSecurity.scala       # Java java.security capture
│       └── Generic.scala   # Fallback for unknown types
│   └── CbomEmitter.scala   # CycloneDX CBOM post-processor
├── util/                   # Utilities
│   ├── Helpers.scala       # Hash functions, I/O utilities
│   ├── ArtifactWrapper.scala # File abstraction
│   ├── FileWalker.scala    # Archive traversal
│   ├── Config.scala        # CLI parsing
│   └── GitOID.scala        # GitOID computation
└── components/             # Component system (plugins)
```

---

## Core Data Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Processing Pipeline                            │
└─────────────────────────────────────────────────────────────────────────┘

1. INPUT                    2. DISCOVERY              3. STRATEGY
┌──────────────┐           ┌──────────────┐          ┌──────────────┐
│ Directory    │           │ Find all     │          │ Group files  │
│ of artifacts │──────────▶│ files        │─────────▶│ by type      │
└──────────────┘           │ + MIME types │          │ (Maven, etc) │
                           └──────────────┘          └──────────────┘
                                                            │
                                                            ▼
4. PROCESSING              5. STORAGE                6. OUTPUT
┌──────────────┐           ┌──────────────┐          ┌──────────────┐
│ Build ADG    │           │ MemStorage   │          │ Write .grd   │
│ for each     │──────────▶│ (low-lock    │─────────▶│ .gri files   │
│ ToProcess    │           │ concurrent)  │          │              │
└──────────────┘           └──────────────┘          └──────────────┘
```

---

## Key Components

### 1. ArtifactWrapper (`util/ArtifactWrapper.scala`)

Abstracts file access for both real files and in-memory buffers:

```scala
sealed trait ArtifactWrapper {
  def path(): String           // Logical path
  def size: Long               // Size in bytes
  def mimeType: String         // Detected MIME type
  def withStream[T](f: InputStream => T): T  // Stream access
}

case class FileWrapper(...)    // Real file on disk
case class ByteWrapper(...)    // In-memory bytes
```

**Design decisions:**
- Files ≤ 64KB (when tempdir is configured) kept in memory
- Files ≤ 32MB (when no tempdir) kept in memory
- Larger files written to temp directory
- .NET assemblies (.dll, .exe) always written to temp files regardless of size (for cilantro processing)
- MIME detection via Apache Tika

> See [`ArtifactWrapper.newWrapper()`](../src/main/scala/io/spicelabs/goatrodeo/util/ArtifactWrapper.scala) at lines 233-265 for the size threshold implementation.

### 2. ToProcess (`omnibor/ToProcess.scala`)

The strategy pattern for processing different artifact types:

```scala
trait ToProcess {
  type MarkerType              // State marker for processing
  type StateType               // Mutable processing state

  def getElementsToProcess(): (Seq[(ArtifactWrapper, MarkerType)], StateType)
  def mimeType: String
  def itemCnt: Int
}

trait ProcessingState[M, S] {
  def beginProcessing(...): S
  def getPurls(...): (Vector[PackageURL], S)
  def getMetadata(...): (TreeMap[String, TreeSet[StringOrPair]], S)
  def finalAugmentation(...): (Item, S)
  def postChildProcessing(...): S
}
```

**Strategy selection order** (matches `ToProcess.scala`):
1. `MavenToProcess` - JAR/POM/sources/javadoc groupings
2. `DockerToProcess` - Docker image manifests and layers
3. `Debian` - .deb packages
4. `DotnetFile` - .NET assemblies
5. `Annatto` - Bun JavaScript bundler outputs
6. `BaharatStrategy` - Saffron-flagged outputs
7. `JvmDistribution` - JDK/JRE installations via `release` file
8. `GradleLockfile` - Gradle lockfile dependency parsing
9. `Certificates` - X.509 certs / CRLs / keystores / PEM bundles / SSH / PGP / private keys
10. `OpenSSLConfig` - OpenSSL `.cnf` configuration files
11. `JavaSecurity` - Java `java.security` policy files
12. `GenericFile` - Everything else (fallback)

### 3. Item (`omnibor/Item.scala`)

Represents a node in the ADG:

```scala
case class Item(
  identifier: String,                    // GitOID (primary key)
  connections: TreeSet[(String, String)], // (EdgeType, target GitOID)
  bodyMimeType: Option[String],          // Body format
  body: Option[AnyRef]                   // ItemMetaData or ItemTagData
)
```

**Edge types** (defined in `Struct.scala`):
- `contains` / `containedBy` - Parent/child relationships
- `aliasFrom` / `aliasTo` - Alternative identifiers (MD5, SHA1, etc.)
- `builtFrom` / `buildsTo` - Source-to-binary relationships

### 4. Storage (`omnibor/Storage.scala`)

In-memory graph storage with low-lock concurrent access:

```scala
trait Storage {
  def exists(gitoid: GitOID): Boolean
  def read(gitoid: GitOID): Option[Item]
  def write(gitoid: GitOID, item: Item): Unit
  def update(gitoid: GitOID, f: Option[Item] => Item): Item
}

class MemStorage extends Storage {
  // Uses per-key locking for writes
  // Immutable snapshots for reads
}
```

**Concurrency model:**
- Reads are lock-free (immutable snapshot)
- Writes use per-GitOID locks (minimal contention)
- Updates are atomic read-modify-write

### 5. FileWalker (`util/FileWalker.scala`)

Handles archive traversal:

```scala
object FileWalker {
  def withinArchiveStream[T](
    artifact: ArtifactWrapper,
    f: (String, ArtifactWrapper) => T
  ): Vector[T]
}
```

**Supported formats:**
- ZIP, JAR, WAR, EAR (via `ZipInputStream`)
- NuGet packages (.nupkg) — detected as ZIP via magic bytes (see [`ArtifactWrapper.isNupkg()`](../src/main/scala/io/spicelabs/goatrodeo/util/ArtifactWrapper.scala) at lines 180-210)
- TAR, TAR.GZ, TAR.BZ2 (via Commons Compress)
- AR, DEB (via Commons Compress)
- ISO (via PalantirIsoReader)
- CPIO (via Commons Compress)

---

## Processing Lifecycle

### Phase 1: File Discovery

```scala
// In Builder.scala
val files = Helpers.findFiles(directory)
val artifacts = files.par.map { file =>
  ArtifactWrapper.newWrapper(file, tempDir)
}
```

- Parallel MIME type detection
- Creates `ArtifactWrapper` for each file

### Phase 2: Strategy Selection

```scala
// In ToProcess.scala
val strategies = Vector(
  MavenToProcess.computeMavenFiles,
  DockerToProcess.computeDockerFiles,
  Debian.computeDebianFiles,
  DotnetFile.computeDotnetFiles,
  Annatto.computeAnnattoFiles,
  BaharatStrategy.computeBaharatFiles,
  JvmDistribution.computeJvmFiles,
  GradleLockfile.computeGradleLockfiles,
  Certificates.computeCertificateFiles,
  OpenSSLConfigToProcess.computeOpenSSLConfigFiles,
  JavaSecurityToProcess.computeJavaSecurityFiles,
  GenericFile.computeGenericFiles  // Must be last
)
```

Each strategy claims files it can handle; unclaimed files fall through to `GenericFile`.

### Strategy Selection vs. Processing Boundary

The strategy framework has a strict separation between **selection** and **processing**:

1. **MIME detection** (completed before strategy selection) is the only stage that may open an `ArtifactWrapper` stream to inspect content. MIME augmenters are registered via `ArtifactWrapper.addMimeTypeAugmenter`. They must be conservative, bounded, and additive.
2. **Strategy selection** (`computeXFiles` functions) receives `ByUUID` and `ByName` maps and must select files using only:
   - Precomputed MIME type (`artifact.mimeType`)
   - Logical path (`artifact.path()`)
   - Size (`artifact.size()`) for thresholds

   Selection functions must **not** call `artifact.withStream`, `artifact.withFile`, or any other method that reads content. Selection must be fast and content-agnostic.
3. **Strategy processing** (`ToProcess.getElementsToProcess` and `ProcessingState` methods) may read the content of already-selected files to parse, resolve dependencies, order files, and emit metadata. All dependency resolution must be among files already selected by that strategy; a strategy may not return to `ByName`/`ByUUID` to claim additional files during processing.

This boundary keeps strategy selection fast and ensures that content inspection happens only during MIME detection (where it is allowed) or during strategy processing (where the strategy already owns the file). If a strategy needs to coordinate multiple files (e.g., OpenSSL `.include` or Java `include` directives), it must select all relevant files by MIME type first and then resolve references within the selected set during processing.

### Phase 3: ADG Building

For each `ToProcess`:

1. **Compute GitOID** - Hash the artifact content
2. **Check block list** - Skip if on block list
3. **Create/merge Item** - Add to Storage
4. **Add aliases** - MD5, SHA1, SHA512 as `aliasFrom` edges
5. **Extract children** - Open archives recursively
6. **Process children** - Recursive ADG building
7. **Add back-references** - `containedBy` edges

### Phase 4: Output

```scala
// In GraphManager.scala
def writeEntries(storage: Storage, destDir: File): Unit
```

Writes:
- `.grd` files - CBOR-encoded Items
- `.gri` files - Index (MD5 hash → file offset)
- `.grc` file - Cluster metadata

Optional CBOM output (`--emit-cbom-dir`):
- After the ADG is written, `Builder` calls `CbomEmitter.emitForStorage(storage, version, dir)`.
- One CycloneDX CBOM JSON file is emitted per root Item.
- The emitter walks `contains` edges, collects cryptographic Items, redacts private keys, and maps the rest to `cryptographic-asset` components.

---

## Extending Goat Rodeo

### Adding a New Artifact Type

1. **Create state class** implementing `ProcessingState`:

```scala
class MyState extends ProcessingState[SingleMarker, MyState] {
  def beginProcessing(...) = ...
  def getPurls(...) = ...
  def getMetadata(...) = ...
  // etc.
}
```

2. **Create ToProcess class**:

```scala
case class MyFile(file: ArtifactWrapper) extends ToProcess {
  type MarkerType = SingleMarker
  type StateType = MyState
  // ...
}
```

3. **Create detection function**:

```scala
object MyFile {
  def computeMyFiles(
    byUUID: ByUUID,
    byName: ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    // Find files of your type, create MyFile instances
  }
}
```

4. **Register in ToProcess.strategies**:

```scala
val strategies = Vector(
  // ... existing strategies ...
  MyFile.computeMyFiles,
  GenericFile.computeGenericFiles  // Keep last
)
```

**Reference example:** the `Certificates` strategy
([`Certificates.scala`](../src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Certificates.scala))
is a comprehensive worked example covering 8 distinct claim types
(X.509 certs, CRLs, Java keystores, PEM bundles, SSH pubkeys + certs,
PGP keys, private keys both unencrypted and encrypted), a sealed
`ClaimedContent` ADT for dispatch, MIME-driven routing in
`classifyAndParse`, per-variant emitters, and a defensive leak sweep
(`assertMetadataStructureNoLeak`) before metadata emission. See
[`info/certificates_strategy.md`](certificates_strategy.md) for the
user-facing strategy documentation and
[`info/certificates/phase-{0..9}-claims.md`](certificates/) for the
phased build-up.

### Adding New Metadata

Metadata keys are defined in `MetadataKeyConstants`:

```scala
object MetadataKeyConstants {
  val NAME = "Name"
  val VERSION = "Version"
  // Add your key here
  val MY_FIELD = "MyField"
}
```

Use in your `ProcessingState.getMetadata`:

```scala
def getMetadata(...) = {
  val tm = TreeMap[String, TreeSet[StringOrPair]](
    MetadataKeyConstants.MY_FIELD -> TreeSet("value")
  )
  (tm, this)
}
```

---

## Testing

### Test Structure

```
src/test/scala/
├── MySuite.scala           # Main integration tests
├── PropertyBasedTestSuite.scala  # Property-based tests
├── HelpersTestSuite.scala  # Utility function tests
├── DockerSuite.scala       # Docker-specific tests
└── ...
```

### Running Tests

```bash
# All tests
sbt test

# Specific suite
sbt "testOnly PropertyBasedTestSuite"

# With coverage
sbt clean coverage test coverageReport
```

### Test Data

Test artifacts are in `test_data/`:
- `jar_test/` - Maven artifacts
- `docker_tests/` - Docker images
- `deb_tests/` - Debian packages
- etc.

Large test files are managed via Git LFS.

---

## Performance Considerations

### Memory Management

- **MemStorage size** grows with processed items
- **`--maxrecords`** limits batch size
- **`--tempdir`** offloads large artifacts to disk

### Thread Tuning

- **CPU-bound** - Use ~= logical CPU count
- **I/O-bound** - May benefit from > CPU count
- **Memory pressure** - Reduce threads

### Bottleneck Identification

- **CPU**: All cores at 100%
- **Memory**: GC pressure, OOM errors
- **I/O**: Low CPU, high disk wait (use `iotop`)

---

## CBOR Serialization

Items are serialized using [CBOR](https://cbor.io/) via the Borer library:

```scala
// Encoding
val bytes = item.encodeCBOR()

// Decoding
val item = Item.decode(bytes)
```

**Why CBOR?**
- Compact binary format
- Faster than JSON
- Schema-less (flexible)
- Deterministic encoding for hashing

---

## Maven GroupId/ArtifactId/Version Resolution

The Maven strategy uses field-level merge for groupId/artifactId/version resolution, implemented in `MavenState.resolveGroupIdArtifactIdVersion()`. Instead of picking the first source that provides all three fields, each field is resolved independently from the highest-priority source that provides it:

1. **External POM (companion)** — the sibling `.pom` file paired with the JAR by `computeMavenFiles`, parsed securely via `PomParser`. This is the HIGHEST priority source because it is the authoritative published Maven metadata (REQ-3). See ADR 0012.
2. **Embedded `pom.properties`** — `META-INF/maven/**/pom.properties` extracted from the JAR. When multiple embedded properties files exist (common in fat/uber JARs), only the one whose artifactId best matches the filename is considered; if none match, this layer is skipped entirely to avoid picking a random dependency's metadata. Matching uses `matchScore`: exact match (score 3) > prefix match with separator (score 2) > reverse prefix match with separator (score 1) > no match (score 0). Among same-score matches, the longest artifactId is preferred. See ADR 0014.
3. **Embedded POM** — `META-INF/maven/**/pom.xml` inside the JAR, selected by the same `matchScore` logic as pom.properties when multiple are present.
4. **MANIFEST.MF** (for groupId and version) / **Filename** (for artifactId) — OSGi headers (`Bundle-SymbolicName` / `Bundle-Version`) and standard headers (`Implementation-Title`, `Implementation-Version`, `Implementation-Vendor-Id`). The Maven Bundle Plugin heuristic extracts the last dot segment as artifactId when `Created-By` indicates the bundle plugin. For artifactId, filename has higher priority than manifest because `Implementation-Title` is human-readable, not a Maven artifactId.
5. **Filename heuristics** (for groupId and version) / **MANIFEST.MF** (for artifactId) — the JAR name is split on the first dash preceding a digit: `artifactId-version[.classifier].ext`. Scala binary suffixes (`_2.13`) and `SNAPSHOT` qualifiers are preserved.

Per-field priority:
- **groupId**: external POM > pom.properties > embedded pom.xml > manifest > filename
- **artifactId**: external POM > pom.properties > embedded pom.xml > filename > manifest
- **version**: external POM > pom.properties > embedded pom.xml > manifest > filename

The companion POM (external POM) is the HIGHEST priority for canonical pURL resolution because it is the authoritative published Maven metadata. pom.properties is filename-gated by `determinePrimaryGroupIdArtifactIdVersion` — it only contributes fields when its artifactId matches the JAR filename via `matchScore` (exact > prefix-with-separator > None), preventing cross-artifact mixing and pURL hijacking via short artifactIds. The key difference from source-level priority: **filename and manifest swap positions for artifactId**. This produces pURLs more likely to exist in Maven Central because the filename usually matches the Maven artifactId, while `Implementation-Title` is a human-readable display name. See ADR 0014 for the matching algorithm details.

`resolveGroupIdArtifactIdVersionFromManifest` returns individual fields without an artifactId gate, allowing the manifest to contribute groupId and version even when it has no artifactId headers.

If no groupId is found from any source but artifactId exists, artifactId is used as both groupId and artifactId (last resort fallback). See ADR 0008 for the full decision rationale, trust model, and security concerns.

### PomParser Integration

`PomParser.scala` replaces the previous `scala.xml.XML` parser. It uses `javax.xml.parsers.DocumentBuilderFactory` hardened against XXE:
- `disallow-doctype-decl=true`
- `external-general-entities=false`
- `load-external-dtd=false`
- `xIncludeAware=false`

If a POM contains a benign DOCTYPE, a stripping fallback removes the declaration and retries parsing. Entity references in the body remain undeclared after stripping, causing the parse to fail safely rather than expanding them.

`PomParser` extracts and interpolates properties (`${key}` → value), walks `<parent>` references for missing groupId/artifactId/version fields, reads `<dependencyManagement>`, and extracts `<licenses>`, `<scm>`, `<organization>`, and `<dependencies>`. The results are stored in `MavenState` as `Option[ParsedPom]` rather than the legacy `scala.xml.NodeSeq`.

### Metadata Emission

Beyond the standard `MetadataKeyConstants` keys, the Maven strategy emits:
- `maven:DEPENDENCIES` — JSON array of all `<dependency>` elements with interpolated versions and merged `dependencyManagement` entries.
- `maven:RuntimeDependencies` — filtered subset excluding `test` and `provided` scopes.
- `maven:SCM_URL` — from `<scm><url>`.
- `LICENSE` — merged from POM `<licenses>`, `Bundle-License`, and `Plugin-License-Name` manifest headers.
- `PUBLISHER` — from `<organization><name>`.
- `maven:Timestamp` — build timestamp from POM or manifest.
- `maven:ParentPOM` — parent groupId/artifactId/version as JSON object.
- `maven:Latest`, `maven:Release`, `maven:Versions` — from `maven-metadata.xml`.
- `maven:JarType`, `maven:NestedJars`, `maven:SpringBootMainClass`, `maven:LayersIdx`, `maven:ClasspathIdx` — for Spring Boot / shaded JARs.
- `maven:WarLibJars` — for WAR archives.
- `maven:EarModules` — for EAR archives.
- `maven:MultiReleaseVersions` — for Multi-Release JARs.
- `maven:JarSigned`, `maven:SignatureFiles` — for signed JARs.
- `maven:ServiceProviders` — for `META-INF/services/*` files.
- `maven:AutomaticModuleName` — from manifest or `module-info.class` (BCEL).
- `maven:ModuleRequires`, `maven:ModuleExports`, `maven:ModuleOpens`, `maven:ModuleProvides`, `maven:ModuleUses` — from JPMS `module-info.class` (BCEL).
- `maven:GraalNativeImage` — from `native-image.properties`.
- `maven:JenkinsPlugin` — for `.jpi`/`.hpi` or Jenkins groupId.
- `osgi:BundleName`, `osgi:BundleSymbolicName`, `osgi:BundleDescription`, `osgi:BundleVendor`, `osgi:BundleDocURL`, `osgi:ExportPackage`, `osgi:ImportPackage`, `osgi:RequireCapability`, `osgi:ProvideCapability`, `osgi:FragmentHost` — from OSGi manifest headers (Export-Package and Import-Package parsed for directives).

**Verified by:**
- `MavenPhase1Suite` — groupId/artifactId/version chain, filename parsing, OSGi manifest, XXE protection, DOCTYPE edge cases.
- `MavenPhase2Suite` — PomParser interpolation, extended metadata, build dates.
- `MavenPhase3Suite` — dependency JSON, license extraction, scope filtering, Plugin-License-Name.
- `MavenPhase4Suite` — parent POM metadata, maven-metadata.xml, pqc_jars end-to-end identity and tags.
- `MavenPhase5Suite` — JAR structure metadata, Spring Boot, shaded, WAR, EAR, multi-release, signatures, ServiceLoader, module name, GraalVM, Jenkins, OSGi full headers.
- `MavenPhase5ModuleInfoSuite` — BCEL-based parsing of JPMS `module-info.class`.
- `MavenPhase5CorpusSuite` — corpus integration tests against all 10 structural `test_data/` artifacts.
- `MavenPropertyTests` — ScalaCheck property tests for interpolation, filename extraction, date parsing, and priority-chain determinism.
- `Phase3MatchingSuite` — exact match > prefix match > None matching algorithm, pURL hijacking prevention, DoS guard.
- `Phase3MatchingPropertySuite` — property tests for exact match preference and short artifactId hijacking prevention.
- `Phase2CanonicalPrioritySuite` — canonical pURL priority chain (external POM > pom.properties > embedded pom.xml > manifest > filename).
- `Phase4SecondaryClassifierSuite` — secondary pURL classifier fix (sources/javadoc JARs emit `?packaging=sources` / `?classifier=javadoc` on ALL pURLs, not just canonical).
- `Phase5MetadataParitySourcesJavadocSuite` — corpus-based metadata parity for sources/javadoc JARs (53 tests, opens real JARs at test time per HS-4, verifies pURL superset, classifier on all pURLs, canonical pURL from companion POM, standalone sources JAR emission). See ADR 0015.
- `Phase6MetadataParityRegularBestPurlSuite` — Maven Central validation for regular JARs (47 tests: 12 "better than the reference scanner" groupId checks, 12 Maven Central coordinate matches, 10 pURL count checks, 12 companion POM priority checks). Coordinates manually verified against Maven Central on 2026-07-08. See ADR 0016.
- `MultiplePurlSuite` — metadata parity for regular JARs/WARs (113 tests, `alias:from` connections, companion POM, fat JAR filtering).
- `BestPurlSuite` — field-level merge produces Maven Central pURLs (2 tests, `resolveGroupIdArtifactIdVersion` with `externalPom=None`).
- `PackageTagIntegrationSuite` — end-to-end processing of real Maven JARs from `test_data/pqc_jars`.

### Sources and Javadoc JAR pURL Emission

Sources JARs (`-sources.jar`) and javadoc JARs (`-javadoc.jar`) are NOT
treated as main JARs (`isMavenArchive` returns `false` for them). They are
processed in two ways:

1. **As companions to main JARs** — `computeMavenFiles` bundles
   sources/javadoc JARs with their main JAR in a `MavenToProcess` entry.
   The main JAR's POM provides the canonical groupId/artifactId/version.
2. **As standalone artifacts** — `computeMavenFiles` second pass claims
   standalone sources/javadoc JARs (no main JAR in the same directory) as
   `MavenToProcess(a, None, None, None, None)`. The canonical pURL falls
   back to JAR contents (pom.properties, manifest) then filename.

All pURLs emitted from sources JARs include `?packaging=sources`. All pURLs
from javadoc JARs include `?classifier=javadoc`. This applies to BOTH
canonical and secondary pURLs (fixed in Phase 4, see ADR 0013).

**Corpus stats**: 3051 sources JARs, 1 javadoc JAR in `test_data/`.
2995 sources JARs have companion POMs. 56 are standalone (no main JAR, no POM).

**Verified by:** `Phase5MetadataParitySourcesJavadocSuite` (53 tests, all pass).

---

## See Also

- [How It Works](goat_rodeo_operation.md) - Operational guide
- [API Reference](goat_rodeo_api.md) - Library API
- [Data Formats](corpus.md) - Output file formats - All documentation

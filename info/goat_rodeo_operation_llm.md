# How Goat Rodeo Works (LLM)

> **Navigation:** [Docs](README.md) | [API](goat_rodeo_api.md) | [Architecture](architecture.md)

## Overview
Pipeline: File Discovery → Strategy Determination → ADG Building.

## Tags
- **Survey Tag** (`--tag`): single run anchor.
- **Sub-Tag** (`--package-tags`): per-package tag created by strategies (Maven, Docker, Baharat, Annatto, Dotnet, JVM/JDK, Gradle). Keys: `tag`, `version`, `date`. JSON marker `"package_tag": true`.
- **Short names** (`--package-tags-short-name`): uses `artifactId` instead of full groupId/artifactId/version for Maven; short name for other strategies where applicable.

## CLI Flags
- `-b, --build`, `-o, --out`, `-t, --threads`, `--maxrecords`, `--block`, `--tempdir`
- `--tag`, `--tag-version`, `--tag-date`
- `--package-tags`, `--package-tags-short-name`
- `--ingested`, `--ignore`, `--file-list`, `--exclude-pattern`

## Operation Phases
1. **File Discovery** — recursive walk, ignore `.`-prefixed. Assign MIME via Tika.
2. **Strategy Determination** — ordered vector of processors. `GenericFile` always last.
   - `MavenToProcess` — groups `pom`+`jar`+`sources`+`javadocs`.
   - `DockerToProcess` — Docker image manifests and layers.
   - `Debian` — `.deb` packages.
   - `DotnetFile` — .NET assemblies.
   - `Annatto` — Bun JS bundler outputs.
   - `BaharatStrategy` — Saffron-flagged outputs.
   - `JvmDistribution` — JDK/JRE `release` file detection.
   - `GradleLockfile` — Gradle lockfile dependency parsing.
   - `Certificates` — X.509/CRL/keystore/SSH/PGP/private keys.
3. **ADG Building** — gitoid per artifact. Block list pruning. Container recursion. `MemStorage` node-level locking + immutable read.

## Maven Identity Resolution (5-layer chain)
First complete `(groupId, artifactId, version)` wins:
1. Embedded `pom.properties` inside JAR.
2. External sibling `.pom`.
3. Embedded `pom.xml` inside JAR.
4. MANIFEST.MF — OSGi headers, `Implementation-Title`, `Bundle-Version`, etc.
5. Filename heuristic — `groupId.artifactId-version.jar` or `artifactId-version.jar`. Scala suffix `_2.13` stays in artifactId.

## Enhanced JAR Metadata (Phase 5)
`MavenState` detects structural JAR types and emits metadata keys:
- **Spring Boot fat JAR**: `JarType=spring-boot-fat-jar`, `NestedJars`, `SpringBootMainClass`, `LayersIdx`, `ClasspathIdx`
- **Shaded JAR**: `JarType=shaded-jar` (marker file or `Created-By: Apache Maven Shade Plugin`)
- **WAR**: `JarType=war`, `WarLibJars`
- **EAR**: `JarType=ear`, `EarModules`
- **Multi-Release JAR**: `JarType=multi-release`, `MultiReleaseVersions`
- **Signed JAR**: `JarSigned=true`, `SignatureFiles`
- **ServiceLoader**: `ServiceProviders` JSON map
- **JPMS**: `AutomaticModuleName`, `ModuleRequires`, `ModuleExports`, `ModuleOpens`, `ModuleProvides`, `ModuleUses`
- **GraalVM**: `GraalNativeImage` properties
- **Jenkins Plugin**: `JenkinsPlugin=true`
- **OSGi**: full header extraction (`osgi:BundleName`, `BundleDescription`, `BundleVendor`, `BundleDocURL`, `ExportPackage`, `ImportPackage`, `RequireCapability`, `ProvideCapability`, `FragmentHost`)

## JVM/JDK Detection (Phase 6)
- Claims files named `release` containing `JAVA_VERSION` or `JAVA_RUNTIME_VERSION`.
- Parses 14+ fields: `JAVA_VERSION`, `JAVA_RUNTIME_VERSION`, `IMPLEMENTOR`, `IMAGE_TYPE`, `OS_ARCH`, `OS_NAME`, `LIBC`, `JVM_VARIANT`, `SEMANTIC_VERSION`, `FULL_VERSION`, `SOURCE_REPO`, `BUILD_SOURCE_REPO`, `JAVA_VERSION_DATE`.
- Generates `pkg:generic/<vendor>/<product>@<version>` pURL with `repository_url` qualifier.
- Vendor mapping: Eclipse Adoptium → `eclipse/temurin`, Oracle → `oracle/jdk`, Azul → `azul/zulu`, Amazon → `amazon/corretto`, IBM → `ibm/jdk`, Microsoft → `microsoft/jdk`, default → `openjdk/jdk`.
- JDK vs JRE detection: `IMAGE_TYPE` authoritative; fallback checks for sibling `bin/javac`.

## Gradle Lockfile (Phase 7)
- Claims `gradle.lockfile`, `buildscript-gradle.lockfile`, and `dependency-locks/*.lockfile`.
- Modern format: `group:artifact:version=config1,config2,...`
- Legacy format: `group:artifact:version` with config derived from filename.
- Generates `pkg:maven/...` pURL for each locked dependency.
- Emits `Dependencies` JSON with Gradle configuration names stored in `scope`.

## POM Parsing (Phase 2)
`PomParser` (hardened, no `scala.xml.XML`):
- groupId/artifactId/version with parent fallback for missing child fields.
- Property interpolation (`${key}`) with 10-level depth cap and cycle detection.
- Secure XML parser: DTD, external entities, entity expansion, XInclude all disabled.

## Dependency Metadata (Phase 3)
- Stored as JSON under `adHoc("maven")("DEPENDENCIES")` and `adHoc("maven")("RuntimeDependencies")`.
- No new edge types.
- Default scope = `compile` when omitted.
- `RuntimeDependencies` subset filters out `test` and `provided`.

## Certificates Strategy Key Behaviors
- **Keystore = single flat Item** with aggregated pURLs/metadata, not one Item per entry.
- **Encrypted material is opaque** — envelope metadata only, no decryption, no password guessing.
- **Private key filtering** (`Certificates.filterLeaks`) drops private-key-containing entries silently (no exception).
- **SSH key format** returns `Option` for all parse failures.

## MemStorage
Low-lock immutable map. Node-level atomic updates. Size scales with `--maxrecords`.

## Threads & Memory
- Default threads = 4.
- Use RAM disk for `--tempdir` (25G+) to avoid NVMe/SSD/HDD writes.
- Memory pressure rises with threads + in-memory `ArtifactWrapper` count.

## pURL Examples
See `goat_rodeo_operation.md` for the full list of pURL patterns generated by the Certificates strategy.

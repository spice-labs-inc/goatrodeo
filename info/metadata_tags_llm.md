# Metadata Tags (LLM Reference)

> **Navigation:** [Docs](README.md) | [Corpus](corpus.md) | [Architecture](architecture.md)

All metadata keys Goat Rodeo can emit, by source strategy. Keys are sorted by source.

## Standard Keys (MetadataKeyConstants)

| Key | Meaning | Format |
|---|---|---|
| NAME | Full name of artifact | String, arbitrary |
| SIMPLE_NAME | Short product name | String, 1–2 words |
| VERSION | Version | Dot-separated, e.g. `1.0.0d4` |
| LOCALE | Locale | `CC-LL`, e.g. `en-US` |
| PUBLIC_KEY | Signing public key | Hex string |
| PUBLISHER | Publisher | String, e.g. `ByteStyle, LLC` |
| PUBLICATION_DATE | Publish/build date | Date string, ideally ISO 8601 |
| COPYRIGHT | Copyright declaration | String |
| DESCRIPTION | Description | String |
| TRADEMARK | Trademark | String |
| ARTIFACTID | Artifact identifier | String |
| LICENSE | License | String or merged from POM + MANIFEST |
| URL | Homepage / download URL | String URL |
| DEPENDENCIES | Dependency list | JSON array of `{group, artifact, version, scope, optional, classifier, type}` |

## Maven Strategy (`maven:*`)

| Key | Meaning | Format |
|---|---|---|
| maven:SCM_URL | POM `<scm><url>` | String URL |
| maven:Timestamp | Build timestamp | ISO 8601 string |
| maven:ParentPOM | POM `<parent>` GAV | JSON `{"groupId": ..., "artifactId": ..., "version": ...}` |
| maven:Latest | `maven-metadata.xml` latest | String |
| maven:Release | `maven-metadata.xml` release | String |
| maven:Versions | All versions in metadata | JSON array of strings |
| maven:JarType | Archive type | `spring-boot-fat-jar`, `shaded-jar`, `war`, `ear`, `multi-release` |
| maven:NestedJars | Spring Boot `BOOT-INF/lib/` | JSON array of path strings |
| maven:SpringBootMainClass | `Start-Class` manifest header | FQCN string |
| maven:LayersIdx | `BOOT-INF/layers.idx` contents | JSON array of strings |
| maven:ClasspathIdx | `BOOT-INF/classpath.idx` contents | JSON array of strings |
| maven:WarLibJars | `WEB-INF/lib/` JARs | JSON array of path strings |
| maven:EarModules | EAR `META-INF/application.xml` modules | JSON array of module objects |
| maven:MultiReleaseVersions | JDK versions in `META-INF/versions/` | JSON array of integers |
| maven:JarSigned | Signature presence | `"true"` if `.SF`/`.RSA`/`.DSA` found |
| maven:SignatureFiles | Signature file names | JSON array of strings |
| maven:ServiceProviders | `META-INF/services/*` mappings | JSON map `{service: [impl1, impl2]}` |
| maven:AutomaticModuleName | `Automatic-Module-Name` manifest header | String |
| maven:GraalNativeImage | `native-image.properties` content | JSON map of properties |
| maven:JenkinsPlugin | Jenkins plugin detection | `"true"` if `.jpi`/`.hpi` or `io.jenkins.plugins.*` group |
| maven:RuntimeDependencies | Filtered dependencies (compile + runtime only) | JSON array (same shape as DEPENDENCIES) |
| maven:ModuleRequires | JPMS `requires` | JSON array of strings |
| maven:ModuleExports | JPMS `exports` | JSON array of strings |
| maven:ModuleOpens | JPMS `opens` | JSON array of strings |
| maven:ModuleProvides | JPMS `provides` | JSON object `{service: [impl1]}` |
| maven:ModuleUses | JPMS `uses` | JSON array of strings |

## OSGi Strategy (`osgi:*`)

| Key | Meaning | Format |
|---|---|---|
| osgi:BundleName | `Bundle-Name` | String |
| osgi:BundleDescription | `Bundle-Description` | String |
| osgi:BundleVendor | `Bundle-Vendor` | String |
| osgi:BundleDocURL | `Bundle-DocURL` | String URL |
| osgi:ExportPackage | Parsed `Export-Package` | JSON array `[{"package": "...", "version": "..."}]` |
| osgi:ImportPackage | Parsed `Import-Package` | JSON array `[{"package": "...", "version": "..."}]` |
| osgi:RequireCapability | `Require-Capability` | String |
| osgi:ProvideCapability | `Provide-Capability` | String |
| osgi:FragmentHost | `Fragment-Host` | String |

## JVM Distribution Strategy (`jvm:*`)

| Key | Meaning | Format |
|---|---|---|
| jvm:Vendor | Vendor namespace | `eclipse`, `oracle`, `azul`, `amazon`, `openjdk`, ... |
| jvm:JavaVersion | `JAVA_VERSION` from `release` | e.g. `21.0.4` |
| jvm:JavaRuntimeVersion | `JAVA_RUNTIME_VERSION` | e.g. `21.0.4+7` |
| jvm:ImageType | `IMAGE_TYPE` (`JDK` or `JRE`) | String |
| jvm:OsArch | `OS_ARCH` | e.g. `x86_64` |
| jvm:OsName | `OS_NAME` | e.g. `linux` |
| jvm:Libc | `LIBC` | e.g. `glibc` |
| jvm:JvmVariant | `JVM_VARIANT` | e.g. `Hotspot` |
| jvm:SemanticVersion | `SEMANTIC_VERSION` | String |
| jvm:FullVersion | `FULL_VERSION` | String |
| jvm:SourceRepo | `SOURCE_REPO` | String URL |
| jvm:BuildSourceRepo | `BUILD_SOURCE_REPO` | String URL |
| jvm:JavaVersionDate | `JAVA_VERSION_DATE` | `YYYY-MM-DD` |
| jvm:IsJDK | JDK classification | `"true"` or `"false"` |

## Gradle Lockfile Strategy (`gradle:*`)

| Key | Meaning | Format |
|---|---|---|
| gradle:DependencyCount | Number of locked dependencies | String integer |

## Verification Sources

- `MavenPhase2Suite`, `MavenPhase3Suite`, `MavenPhase5Suite`, `MavenPhase5ModuleInfoSuite`, `MavenPhase5CorpusSuite`
- `JvmDistributionSuite`
- `GradleLockfileSuite`
- `MavenPropertyTests`

## Dependency JSON Shape

Maven/Gradle dependencies serialize as:
```json
[
  {"group": "org.example", "artifact": "mylib", "version": "2.0",
   "scope": "compile", "optional": false, "classifier": null, "type": null}
]
```

Dotnet dependencies serialize as:
```json
{"dependencies": [
  {"name": "assemblyName", "version": "1.2.3",
   "public_key_token": "...", "public_key": "..."}
]}
```

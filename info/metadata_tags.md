# Metadata Tags

> **Navigation:** [Documentation Index](README.md) | [Corpus Format](corpus.md) | [Architecture](architecture.md)

Attaching metadata to a processed file can be useful for categorizing and searching for particular patterns across data sets.
As such, it's useful to have a standard set of names for the tags that are associated with a piece of metadata.

There are challenges in doing this because different metadata providers can disagree on the names of metadata tags as well as the semantics of the metadata and the formatting of the actual data. It should be understood that goat rodeo doesn't have direct control over every metadata provider and that there will be differences in any of names/semantics/formatting. New providers should try to provide as many of these pieces of metadata.

Standard tags are defined in MetadataKeyConstants

| Constant Name | String Value | Meaning | Format |
| ----          | ----         |  ----   |  ----  
| NAME          | Name         | The full name of the artifact | String, arbitrary. e.g., "Splunge JSON checker." |
| SIMPLE_NAME   | SimpleName   | A simplified name for the product | String, one or two words. e.g. "Splunge" |
| VERSION       | Version      | A version for the atifact | String, ideally dot-separated fields. e.g. 1.0.0d4 |
| LOCALE        | Locale       | A descriptor for the locale of the artifact or default locale if it has several | String, ideally 2 char country code dash 2 char language code |
| PUBLIC_KEY    | PublicKey    | The public key used to sign the artifact, if any. | String of hex bytes. |
| PUBLISHER     | Publisher    | The publisher of the artifact. | String, e.g. "ByteStyle, LLC" |
| PUBLICATION_DATE | PublicationDate | The date when the artifact was published. For code, this might be the day that it was compiled. | String, Date, hopefully something easily parsable. |
| COPYRIGHT     | Copyright    | A copyright decalarion if available | String e.g. "Copyright (c) 2020, all rights reserved" |
| DESCRIPTION   | Description  | A description of the artifact  | String e.g. "A library to check JSON streams for validity" |
| TRADEMARK     | Trademark    | A trademark declaration if available | String e.g. "Splunge is a registered trademark of ByteStyle" |
| ARTIFACTID    | ArtifactID   | An identifier for the artifact | String |
| LICENSE       | License      | The license for the artifact | String e.g. "This work is openly licensed via CC BY 4.0" |
| MAVEN_SCM_URL | maven:SCM_URL | The SCM URL from the POM `<scm><url>` element | String URL |
| URL           | URL           | The home page or download URL for the artifact | String URL |
| PUBLISHER     | Publisher     | The publisher of the artifact | String, e.g. "ByteStyle, LLC" |
| DEPENDENCIES  | Dependencies  | A list of the dependencies | String, formatted as JSON. See below. |
| RuntimeDependencies | maven:RuntimeDependencies | Subset of dependencies whose scope is `compile` or `runtime`; excludes `test` and `provided` | String, formatted as JSON |
| LICENSE       | License      | The license for the artifact | String. Merged from POM `<licenses>` and `Bundle-License` manifest header. |
| Timestamp | maven:Timestamp | The build timestamp extracted from POM or manifest | String, ISO 8601 date |
| ParentPOM | maven:ParentPOM | Parent POM GAV from `<parent>` element | JSON `{"groupId": ..., "artifactId": ..., "version": ...}` |
| Latest | maven:Latest | Latest version from `maven-metadata.xml` | String |
| Release | maven:Release | Release version from `maven-metadata.xml` | String |
| Versions | maven:Versions | All versions from `maven-metadata.xml` | JSON array of strings |
| JarType | maven:JarType | Detected archive type: `spring-boot-fat-jar`, `shaded-jar`, `war`, `ear`, `multi-release` | String |
| NestedJars | maven:NestedJars | JARs inside `BOOT-INF/lib/` (Spring Boot) | JSON array of path strings |
| SpringBootMainClass | maven:SpringBootMainClass | `Start-Class` from Spring Boot manifest | String, fully-qualified class name |
| LayersIdx | maven:LayersIdx | Contents of `BOOT-INF/layers.idx` | JSON array of strings |
| ClasspathIdx | maven:ClasspathIdx | Contents of `BOOT-INF/classpath.idx` | JSON array of strings |
| WarLibJars | maven:WarLibJars | JARs inside `WEB-INF/lib/` | JSON array of path strings |
| EarModules | maven:EarModules | Modules from `META-INF/application.xml` | JSON array of module objects |
| MultiReleaseVersions | maven:MultiReleaseVersions | JDK version numbers in `META-INF/versions/` | JSON array of integers |
| JarSigned | maven:JarSigned | `true` if `.SF`/`.RSA`/`.DSA` found in `META-INF/` | String `"true"` |
| SignatureFiles | maven:SignatureFiles | List of signature file names | JSON array of strings |
| ServiceProviders | maven:ServiceProviders | `META-INF/services/*` mappings | JSON map `{service: [impl1, impl2]}` |
| AutomaticModuleName | maven:AutomaticModuleName | `Automatic-Module-Name` from manifest | String |
| GraalNativeImage | maven:GraalNativeImage | `native-image.properties` content | JSON map of properties |
| JenkinsPlugin | maven:JenkinsPlugin | `true` if `.jpi`/`.hpi` or `Group-Id: io.jenkins.plugins.*` | String `"true"` |
| BundleName | osgi:BundleName | `Bundle-Name` from OSGi manifest | String |
| BundleDescription | osgi:BundleDescription | `Bundle-Description` from OSGi manifest | String |
| BundleVendor | osgi:BundleVendor | `Bundle-Vendor` from OSGi manifest | String |
| BundleDocURL | osgi:BundleDocURL | `Bundle-DocURL` from OSGi manifest | String URL |
| ExportPackage | osgi:ExportPackage | Parsed `Export-Package` header (packages with directives) | JSON array of objects `[{"package": "...", "version": "..."}]` |
| ImportPackage | osgi:ImportPackage | Parsed `Import-Package` header (packages with directives) | JSON array of objects `[{"package": "...", "version": "..."}]` |
| RequireCapability | osgi:RequireCapability | `Require-Capability` from OSGi manifest | String |
| ProvideCapability | osgi:ProvideCapability | `Provide-Capability` from OSGi manifest | String |
| FragmentHost | osgi:FragmentHost | `Fragment-Host` from OSGi manifest | String |
| ModuleRequires | maven:ModuleRequires | JPMS module requires (from `module-info.class`) | JSON array of strings |
| ModuleExports | maven:ModuleExports | JPMS module exports (from `module-info.class`) | JSON array of strings |
| ModuleOpens | maven:ModuleOpens | JPMS module opens (from `module-info.class`) | JSON array of strings |
| ModuleProvides | maven:ModuleProvides | JPMS module provides (from `module-info.class`) | JSON object `{service: [impl1]}` |
| ModuleUses | maven:ModuleUses | JPMS module uses (from `module-info.class`) | JSON array of strings |
| Vendor | jvm:Vendor | JVM vendor namespace (e.g., `eclipse`, `oracle`, `azul`) | String |
| JavaVersion | jvm:JavaVersion | `JAVA_VERSION` from `release` file | String, e.g. `21.0.4` |
| JavaRuntimeVersion | jvm:JavaRuntimeVersion | `JAVA_RUNTIME_VERSION` from `release` file | String, e.g. `21.0.4+7` |
| ImageType | jvm:ImageType | `IMAGE_TYPE` (`JDK` or `JRE`) | String |
| OsArch | jvm:OsArch | `OS_ARCH` from `release` file | String, e.g. `x86_64` |
| OsName | jvm:OsName | `OS_NAME` from `release` file | String, e.g. `linux` |
| Libc | jvm:Libc | `LIBC` from `release` file | String, e.g. `glibc` |
| JvmVariant | jvm:JvmVariant | `JVM_VARIANT` from `release` file | String, e.g. `Hotspot` |
| SemanticVersion | jvm:SemanticVersion | `SEMANTIC_VERSION` from `release` file | String |
| FullVersion | jvm:FullVersion | `FULL_VERSION` from `release` file | String |
| SourceRepo | jvm:SourceRepo | `SOURCE_REPO` from `release` file | String URL |
| BuildSourceRepo | jvm:BuildSourceRepo | `BUILD_SOURCE_REPO` from `release` file | String URL |
| JavaVersionDate | jvm:JavaVersionDate | `JAVA_VERSION_DATE` from `release` file | String `YYYY-MM-DD` |
| IsJDK | jvm:IsJDK | `true` if JDK, `false` if JRE | String `"true"` or `"false"` |
| CanonicalPurl | CanonicalPurl | The canonical pURL for the artifact, resolved via field-level merge of pom.properties, pom.xml, MANIFEST.MF, and filename | String, pURL format `pkg:maven/groupId/artifactId@version` |

**Verified by:**
- `MavenPhase2Suite` — `MavenState getMetadata includes POM name as NAME key`, `getMetadata includes POM description as DESCRIPTION key`, `getMetadata includes POM URL as URL key`, `getMetadata includes organization as PUBLISHER key`, `getMetadata includes SCM URL as adHoc key`.
- `MavenPhase2Suite` — `Bundle-License from JAR manifest appears in metadata`.
- `MavenPhase3Suite` — `MavenState - dependencies appear in metadata as JSON`, `MavenState - no Dependencies key when no deps`, `RuntimeDependencies excludes test and provided scope`, `All deps include scope in metadata JSON`, `MavenState - extracts Plugin-License-Name from MANIFEST`.
- `MavenPhase5Suite` — `MavenState - extracts full OSGi headers including Export-Package`.
- `MavenPhase5ModuleInfoSuite` — `MavenState - extracts module-info.class metadata via BCEL`.
- `MavenPhase5CorpusSuite` — corpus integration tests for all 10 structural JAR types.
- `MavenPropertyTests` — `resolveGAV: embeddedProps always wins when complete`, `resolveGAV: falls through each layer deterministically`, `field-merge: monotonicity — per-field priority is respected across all sources`, `field-merge: filename artifactId beats manifest Implementation-Title`.
- `BestPurlSuite` — `commons-codec-1.2: field-level merge produces Maven Central pURL`, `commons-lang-2.4: field-level merge produces Maven Central pURL`.
- `MavenPhase1Suite` — `field-merge: filename artifactId beats manifest Implementation-Title`, `field-merge: swap verification`, `field-merge: manifest provides groupId/version when no artifactId headers`, `security: version masking — manifest version with pom.properties identity`.
- `JvmDistributionSuite` — `JvmState - parses release file with all fields`, `JvmState - generates pURL for JDK`, `corpus adoptium-jdk21 produces pURL and metadata`.
- `GradleLockfileSuite` — `GradleLockfile - parses modern lockfile format`, `GradleLockfile - generates pURLs for each dependency`, `GradleLockfile - preserves configuration list in metadata`.

# Dependencies

If the artifact doesn't have all of its dependencies in hand, it is necessary to provide the information for the relationships between the artifact and its dependencies manually.

This information is formatted as JSON and will look like this:
```json
[
  {
    "group": "org.example",
    "artifact": "mylib",
    "version": "2.0",
    "scope": "compile",
    "optional": false,
    "classifier": null,
    "type": null
  }
]
```
The Maven strategy produces this JSON from the parsed POM `<dependencies>` and `<dependencyManagement>` sections, with property interpolation applied. The `RuntimeDependencies` subset is a filtered copy containing only entries whose resolved scope is `compile` or `runtime`.


If the artifact doesn't have all of its dependencies in hand, it is necessary to provide the information for the relationships between the artifact and its dependencies manually.

This information is formatted as JSON and will look like this:
```json
{
    "dependencies": [
        {
            "name": "assembly name of the reference, does not include a file extension",
            "version": "the version of the assembly usually in the form Maj.Min.Rev",
            "public_key_token": "the public key token of the assembly, a hex string",
            "public_key": "the public key of the assembly IF ANY, a hex string"
        },
    ]
}
```
The `public_key` entry is optional.
Entries in the `dependencies` collection are sorted by name.


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
| LICENSE       | License      | The lisence for the artifact | String e.g. "This work is openly licensed via CC BY 4.0" |
| MAVEN_SCM_URL | maven:SCM_URL | The SCM URL from the POM `<scm><url>` element | String URL |
| MAVEN_SCM_URL | maven:SCM_URL | Alias for maven:SCM_URL | String URL |
| URL           | URL           | The home page or download URL for the artifact | String URL |
| PUBLISHER     | Publisher     | The publisher of the artifact | String, e.g. "ByteStyle, LLC" |
| DEPENDENCIES  | Dependencies  | A list of the dependencies | String, formatted as JSON. See below. |
| RuntimeDependencies | maven:RuntimeDependencies | Subset of dependencies whose scope is `compile` or `runtime`; excludes `test` and `provided` | String, formatted as JSON |
| LICENSE       | License      | The license for the artifact | String. Merged from POM `<licenses>` and `Bundle-License` manifest header. |

**Verified by:**
- `MavenPhase2Suite` — `MavenState getMetadata includes POM name as NAME key`, `getMetadata includes POM description as DESCRIPTION key`, `getMetadata includes POM URL as URL key`, `getMetadata includes organization as PUBLISHER key`, `getMetadata includes SCM URL as adHoc key`.
- `MavenPhase2Suite` — `Bundle-License from JAR manifest appears in metadata`.
- `MavenPhase3Suite` — `MavenState - dependencies appear in metadata as JSON`, `MavenState - no Dependencies key when no deps`, `RuntimeDependencies excludes test and provided scope`, `All deps include scope in metadata JSON`.
- `MavenPropertyTests` — `resolveGAV: embeddedProps always wins when complete`, `resolveGAV: falls through each layer deterministically`.

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


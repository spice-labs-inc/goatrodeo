# Metadata Tags

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
| DEPENDENCIES  | Dependencies | A list of the dependencies | String, formatted as JSON. See below. |


# Dependencies

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


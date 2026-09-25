# iOS app bundle (.ipa) integration

> **Navigation:** [Documentation Index](README.md)

Goat Rodeo processes iOS app distributions (`.ipa` archives and unpacked
`.app` bundle trees) into the ADG: bundle identity, provisioning
signatures, and the signing certificate chain.

## What an .ipa is

An `.ipa` is a ZIP archive containing `Payload/<App>.app/` with:

- the **Mach-O executable** (opaque to Goat Rodeo today; Saffron Mach-O
  support is a future integration seam),
- **`Info.plist`** — frequently a **binary plist** (`bplist00`) carrying
  `CFBundleIdentifier`, `CFBundleShortVersionString`, `CFBundleVersion`,
  `CFBundleName`, `MinimumOSVersion`, and the ATS transport-security policy,
- **`embedded.mobileprovision`** — a CMS/PKCS#7 SignedData whose payload is
  a plist (TeamIdentifier, AppIDName, Entitlements, ProvisionedDevices,
  ExpirationDate) and whose certificate chain is the signing chain,
- `_CodeSignature/`, `Frameworks/`, `Assets.car`, localized resources.

## How it works

1. **Identification** — `ArtifactWrapper` massages `.ipa` + ZIP magic
   (`PK\x03\x04` or empty-ZIP `PK\x05\x06`) to `application/zip` (see
   [MIME types](mime_types.md)), so the container is walked by the ZIP
   branch.
2. **Claim** — `IpaStrategy.computeIpaFiles` claims `.ipa` zip containers
   plus bare bundle members (`Payload/<App>.app/Info.plist`,
   `embedded.mobileprovision`) that appear without a container (unpacked
   `.app` trees).
3. **Accumulation** — the app bundle's `Info.plist` and
   `embedded.mobileprovision` are harvested from the container's **direct
   children** via the parent-scope pattern (Maven/Dotnet analog): only the
   plist whose parent component ends in `.app` counts — a nested
   framework's `Info.plist`
   (`Payload/<App>.app/Frameworks/X.framework/Info.plist`) cannot clobber
   the app's identity. `applyAccumulatedAugmentation` performs the single
   `store.write` merge after the children are processed.
4. **Certificates** — the provisioning CMS chain (bounded at 32 certs) is
   emitted as certificate Items using the Certificates strategy's metadata
   shape (one code path: `Certificates.perCertMetadata`).
5. **pURL** — a provisional `pkg:apple/ios/<bundle-id>@<version>` is
   emitted (project-owner decision 2026-09-25; provisional until the
   purl-spec ratifies an app-store type; arch qualifier omitted until
   fat-slice enumeration exists).

Parsing of plists (binary and XML) is shared: see
[plist parsing](plist_parsing.md). Plist/CMS blobs over the 16 MiB cap are
refused (never truncated-parsed).

## Metadata keys (`ipa:` prefix)

| Key | Source | Example |
|---|---|---|
| `ipa:BundleIdentifier` | `Info.plist` `CFBundleIdentifier` | `infosecadventures.allsafe` |
| `ipa:BundleShortVersion` | `CFBundleShortVersionString` | `1.0` |
| `ipa:BundleVersion` | `CFBundleVersion` | `1` |
| `ipa:BundleName` | `CFBundleName` | `Allsafe` |
| `ipa:MinimumOS` | `MinimumOSVersion` | `16.1.1` |
| `ipa:TeamIdentifier` | mobileprovision payload | `XXXXXXXXXX` |
| `ipa:AppIDName` | mobileprovision payload | `Allsafe` |
| `ipa:ProvisioningExpiration` | mobileprovision `ExpirationDate` | ISO-8601 |
| `ipa:Entitlements` | mobileprovision `Entitlements` | compact JSON |
| `ipa:ProvisionedDeviceCount` | `ProvisionedDevices` array length | `2` |

Signing certs land as separate Items with the `Certificates:` metadata
shape (`Certificates:SubjectDN`, `Certificates:CertSha256`, …).

## MobileTls ATS on binary plists

The MobileTls strategy's ATS capture (`MobileTls:ats_arbitrary_loads`,
`ats_exceptions`, `ats_local_networking`, `FileType=apple-ats`) now reads
`Info.plist` through the shared plist parser, so **binary plists** (the
real-device form) produce the same flags as XML plists. The legacy text
fallback applies only when the full content was readable — an oversized or
truncated blob is never interpreted as text (the truncated object table of
a binary plist contains ASCII key strings that would false-positive).

## Example query

After processing an `.ipa` with the builder:

```
$ ls <out>/purls.txt | grep apple
pkg:apple/ios/infosecadventures.allsafe@1.0

# the .ipa item carries:
ipa:BundleIdentifier = infosecadventures.allsafe
ipa:BundleShortVersion = 1.0
ipa:TeamIdentifier = <team id from the provisioning profile>
ipa:Entitlements = {"application-identifier": "...", "get-task-allow": false}
# plus certificate items with Certificates:SubjectDN etc. for the signing chain
```

## Claims and their tests

| # | Claim | Verified by |
|---|---|---|
| I1 | A real-ZIP `.ipa` is identified and walked; bundle members appear as children | `IpaDetectionSuite.Ipa-0.1` |
| I2 | Non-zip `.ipa` is not massaged; plain ZIPs and bare plists are unaffected | `Ipa-0.2`, `Ipa-0.3`, `Ipa-0.7` |
| I3 | Renamed `.zip`→`.ipa` walks; empty archives walk; the massage decision equals magic ∧ extension (property) | `Ipa-0.4`, `Ipa-0.5`, `Ipa-0.6` |
| I4 | The fetched real corpus `allsafe-ios.ipa` is claimed, walked, and removed from the remainder maps | `Ipa-0.8` |
| I5 | The strategy claims `.ipa` containers with no residue | `IpaStrategySuite.Ipa-2.1` |
| I6 | Accumulated bundle metadata lands on the container item | `Ipa-2.2` |
| I7 | A nested framework's plist cannot clobber the app bundle's identity | `Ipa-2.3` |
| I8 | Bare bundle members (unpacked `.app`) claim and emit metadata | `Ipa-2.4` |
| I9 | Hostile mobileprovision → no exception, no certs, no metadata | `Ipa-2.5` |
| IA | Plain zips without `Payload/` are not claimed | `Ipa-2.6` |
| IB | The provisioning cert chain lands as certificate Items in the Certificates metadata shape; team/app-id from the payload plist | `Ipa-2.7` |
| IC | `ipa:` metadata contains no private-key material or raw binary blobs; entitlements are JSON values | `Ipa-2.8` |
| ID | An `.ipa` with no plist produces a container item, no `ipa:*` keys, no exception | `Ipa-2.9` |
| IE | Provisional pURL emitted as `pkg:apple/ios/<bundle-id>@<version>` | `Ipa-2.a` |
| IF | Real corpus end-to-end: metadata + certs + pURL from genuine bytes | `Ipa-2.b` |
| IG | Binary-plist ATS flags match the XML twin; exception domains and local networking survive; absent ATS → no flags | `MobileTlsSuite.Mtl-3.1`, `Mtl-3.2`, `Mtl-3.3` |
| IH | Invalid plist bytes → no ATS, no exception; strategy-level binary-plist ATS pipeline | `Mtl-3.4`, `Mtl-3.6` |
| II | Oversized plist refused (never truncated-parsed as ATS) | `Mtl-3.7` |

## Related

- `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/Ipa.scala`
- `src/main/scala/io/spicelabs/goatrodeo/omnibor/strategies/MobileTls.scala`
- `src/main/scala/io/spicelabs/goatrodeo/util/BinaryPlistParser.scala`
- [plist parsing](plist_parsing.md) · [MIME types](mime_types.md) ·
  [metadata tags](metadata_tags.md)
- `src/test/scala/io/spicelabs/goatrodeo/omnibor/IpaDetectionSuite.scala`,
  `.../strategies/IpaStrategySuite.scala`, `.../strategies/MobileTlsSuite.scala`